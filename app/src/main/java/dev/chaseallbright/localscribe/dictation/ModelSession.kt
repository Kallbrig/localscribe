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

    /** How long models stay resident with no focus or dictation activity. */
    private const val IDLE_TIMEOUT_MILLIS = 5 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var engine: ModelSessionEngine<WhisperBridge, AutoCleaner>? = null

    private fun engineFor(context: Context): ModelSessionEngine<WhisperBridge, AutoCleaner> {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            val appContext = context.applicationContext
            return ModelSessionEngine(
                scope = scope,
                prewarmCleaner = ModelManager(appContext).totalRamGb() >= RamTier.CLEANUP_UPGRADE_MIN_GB,
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
                releaseCleaner = { it.close() }
            ).also { engine = it }
        }
    }

    /**
     * Best-effort background warm-up, triggered by focus events. Never downloads: a focused
     * text field must not silently kick off a 150MB-1.1GB fetch, so this only pre-warms models
     * that are already on disk (downloads happen on the [withModels]/acquire path, when the
     * user has explicitly started dictating, matching Settings' "downloads on first use" copy).
     * Also keeps the first-call `totalRamGb()` binder IPC off the caller's thread.
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
            if (manager.totalRamGb() >= RamTier.CLEANUP_UPGRADE_MIN_GB &&
                !manager.isCleanupModelReady(preferences.cleanupTier)
            ) {
                Log.d(TAG, "Skipping prewarm: cleanup model not downloaded yet")
                return@launch
            }
            engineFor(appContext).prewarm()
        }
    }

    /**
     * Runs [block] with pinned models; the pin is always released afterward, success or
     * failure. The handles passed to [block] must not be retained beyond it -- they may be
     * released the instant it returns.
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
     */
    fun onTrimMemory(level: Int) {
        val underPressure = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (underPressure) engine?.onTrimMemory()
    }
}
