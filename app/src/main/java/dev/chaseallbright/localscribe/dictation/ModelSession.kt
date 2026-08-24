package dev.chaseallbright.localscribe.dictation

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import dev.chaseallbright.localscribe.bridge.WhisperBridge
import dev.chaseallbright.localscribe.domain.AutoCleaner
import dev.chaseallbright.localscribe.models.ModelManager
import dev.chaseallbright.localscribe.models.RamTier
import dev.chaseallbright.localscribe.settings.AppPreferences
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-level owner of the resident whisper + cleanup models. Same in-process singleton
 * pattern as [DictationController]; all three services share this process. Loaders read
 * AppPreferences at load time, so a tier change only needs [invalidate], not reconstruction.
 *
 * [withModels] is the preferred way to run a dictation: it pins the models for the block
 * and always releases the pin, even if the block throws. The engine's scope lives for the
 * whole process, which the engine requires (releases run on it).
 */
object ModelSession {
    private const val TAG = "ModelSession"

    /**
     * How long models stay resident with no focus or dictation activity. This is a rolling
     * window, not a hard cap on total residency: each [prewarm] (e.g. from a focus event) and
     * each completed dictation re-arms the timer, so continuous activity -- repeated field
     * focus, back-to-back dictations -- can keep the models resident well beyond a single
     * [IDLE_TIMEOUT_MILLIS] window.
     */
    private const val IDLE_TIMEOUT_MILLIS = 5 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var engine: ModelSessionEngine<WhisperBridge, AutoCleaner>? = null

    private fun engineFor(context: Context): ModelSessionEngine<WhisperBridge, AutoCleaner> {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            val appContext = context.applicationContext
            // engineFor runs at most once per process (guarded by the engine singleton check
            // above), so this is the only totalRamGb() binder call the predicate below needs --
            // captured in the closure rather than re-read on every prewarm() call.
            val ramGb = ModelManager(appContext).totalRamGb()
            return ModelSessionEngine(
                scope = scope,
                // Re-checked on every prewarm() call by the engine (only while the cleaner is
                // still unloaded), so a cleanup download that finishes later is picked up
                // without a fresh engine -- and a missing cleanup model never blocks whisper.
                prewarmCleaner = {
                    ramGb >= RamTier.CLEANUP_UPGRADE_MIN_GB &&
                        ModelManager(appContext).isCleanupModelReady(AppPreferences(appContext).cleanupTier)
                },
                idleTimeoutMillis = IDLE_TIMEOUT_MILLIS,
                loadWhisper = {
                    val preferences = AppPreferences(appContext)
                    val file = ModelManager(appContext).ensureWhisperModel(preferences.whisperTier)
                    WhisperBridge.load(file.absolutePath) ?: error("Failed to load speech model")
                },
                loadCleaner = {
                    val preferences = AppPreferences(appContext)
                    val file = try {
                        ModelManager(appContext).ensureCleanupModel(preferences.cleanupTier)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Cleanup model unavailable; falling back to rules cleanup", e)
                        null
                    }
                    AutoCleaner(file?.absolutePath)
                },
                releaseWhisper = { it.release() },
                releaseCleaner = { it.close() },
                cleanerComplete = { it.isLlmLoaded }
            ).also { engine = it }
        }
    }

    /**
     * Best-effort background warm-up, triggered by focus events. Never downloads and never
     * runs a heavy load unconditionally: a focused text field must not silently kick off a
     * 150MB-1.1GB fetch, so this only pre-warms the whisper model when it's already on disk
     * (downloads happen on the [withModels]/acquire path, when the user has explicitly started
     * dictating, matching Settings' "downloads on first use" copy). Whether the cleanup model
     * also gets pre-warmed is decided per call by the engine's cleaner predicate -- a missing
     * cleanup model must never block whisper prewarm, so that gate lives there, not here.
     */
    fun prewarm(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val preferences = AppPreferences(appContext)
            val manager = ModelManager(appContext)
            if (!manager.isWhisperModelReady(preferences.whisperTier)) {
                Log.d(TAG, "Skipping prewarm: whisper model not downloaded yet")
                return@launch
            }
            engineFor(appContext).prewarm()
        }
    }

    /**
     * Runs [block] with pinned models; the pin is always released afterward, success or
     * failure. The handles passed to [block] must not be retained beyond it -- they may be
     * released the instant it returns.
     *
     * Assumes a single dictation at a time: the native handles are not safe for concurrent
     * use. Today's UI can only ever have one dictation in flight, so this is never exercised,
     * but any future second entry point (e.g. a second overlay, a widget) must add its own
     * serialization before calling this concurrently with an existing dictation.
     */
    suspend fun <T> withModels(
        context: Context,
        block: suspend (LoadedModels<WhisperBridge, AutoCleaner>) -> T
    ): T = engineFor(context).withModels(block)

    fun onFocusLost() = engine?.onFocusLost() ?: Unit

    fun invalidate() = engine?.invalidate() ?: Unit

    /**
     * Releases resident models under genuine system memory pressure. Native model memory can
     * run up to ~1.5GB resident (whisper + cleanup), so this is worth reacting to.
     *
     * Only [ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW], [ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL],
     * and the `>= TRIM_MEMORY_BACKGROUND` background levels count as pressure. Deliberately
     * excludes [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN]: that fires whenever our own UI
     * (e.g. the overlay) is hidden, which happens constantly during normal use and has nothing
     * to do with whether the models should stay resident.
     *
     * Honesty note: on Android 14+ (API 34), the platform no longer delivers any of the trim
     * levels *this filter accepts* -- [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN] is still
     * delivered as before, but that's the level deliberately excluded above, so it doesn't help.
     * This hook is therefore only effective on API 26-33; on API 34+ devices the idle timeout
     * is the sole governor of resident model lifetime. That's an accepted trade-off, not a bug:
     * it costs at most [IDLE_TIMEOUT_MILLIS] of extra residency under memory pressure on modern
     * devices, rather than leaving models resident indefinitely.
     */
    fun onTrimMemory(level: Int) {
        val underPressure = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (underPressure) engine?.onTrimMemory()
    }
}
