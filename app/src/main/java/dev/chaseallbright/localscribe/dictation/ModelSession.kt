package dev.chaseallbright.localscribe.dictation

import android.content.ComponentCallbacks2
import android.content.Context
import dev.chaseallbright.localscribe.bridge.WhisperBridge
import dev.chaseallbright.localscribe.domain.AutoCleaner
import dev.chaseallbright.localscribe.models.ModelManager
import dev.chaseallbright.localscribe.models.RamTier
import dev.chaseallbright.localscribe.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    /** How long models stay resident with no focus or dictation activity. */
    private const val IDLE_TIMEOUT_MILLIS = 5 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var engine: ModelSessionEngine<WhisperBridge, AutoCleaner>? = null

    private fun engine(context: Context): ModelSessionEngine<WhisperBridge, AutoCleaner> {
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
                    val file = runCatching {
                        ModelManager(appContext).ensureCleanupModel(preferences.cleanupTier)
                    }.getOrNull()
                    AutoCleaner(file?.absolutePath)
                },
                releaseWhisper = { it.release() },
                releaseCleaner = { it.close() }
            ).also { engine = it }
        }
    }

    fun prewarm(context: Context) = engine(context).prewarm()

    /** Runs [block] with pinned models; the pin is always released afterwards. */
    suspend fun <T> withModels(
        context: Context,
        block: suspend (LoadedModels<WhisperBridge, AutoCleaner>) -> T
    ): T = engine(context).withModels(block)

    fun onFocusLost() = engine?.onFocusLost() ?: Unit

    fun invalidate() = engine?.invalidate() ?: Unit

    fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            engine?.onTrimMemory()
        }
    }
}
