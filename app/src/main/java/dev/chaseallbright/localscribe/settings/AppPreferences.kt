package dev.chaseallbright.localscribe.settings

import android.content.Context
import dev.chaseallbright.localscribe.audio.RecordingLimit
import dev.chaseallbright.localscribe.domain.CleanupMode
import dev.chaseallbright.localscribe.models.CleanupModelTier
import dev.chaseallbright.localscribe.models.ModelManager
import dev.chaseallbright.localscribe.models.WhisperModelTier

/**
 * Small SharedPreferences-backed settings store. Unset model tiers fall back to
 * [ModelManager]'s RAM-tiered defaults rather than a hardcoded choice.
 */
class AppPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelManager = ModelManager(context.applicationContext)

    var whisperTier: WhisperModelTier
        get() = prefs.getString(KEY_WHISPER_TIER, null)
            ?.let { id -> WhisperModelTier.entries.find { it.id == id } }
            ?: modelManager.defaultWhisperTier()
        set(value) = prefs.edit().putString(KEY_WHISPER_TIER, value.id).apply()

    var cleanupTier: CleanupModelTier
        get() = prefs.getString(KEY_CLEANUP_TIER, null)
            ?.let { id -> CleanupModelTier.entries.find { it.id == id } }
            ?: modelManager.defaultCleanupTier()
        set(value) = prefs.edit().putString(KEY_CLEANUP_TIER, value.id).apply()

    var cleanupMode: CleanupMode
        get() = prefs.getString(KEY_CLEANUP_MODE, null)
            ?.let { name -> runCatching { CleanupMode.valueOf(name) }.getOrNull() }
            ?: CleanupMode.STANDARD
        set(value) = prefs.edit().putString(KEY_CLEANUP_MODE, value.name).apply()

    var recordingLimit: RecordingLimit
        get() = prefs.getString(KEY_RECORDING_LIMIT, null)
            ?.let { id -> RecordingLimit.entries.find { it.id == id } }
            ?: RecordingLimit.DEFAULT
        set(value) = prefs.edit().putString(KEY_RECORDING_LIMIT, value.id).apply()

    private companion object {
        const val PREFS_NAME = "localscribe_settings"
        const val KEY_WHISPER_TIER = "whisper_tier"
        const val KEY_CLEANUP_TIER = "cleanup_tier"
        const val KEY_CLEANUP_MODE = "cleanup_mode"
        const val KEY_RECORDING_LIMIT = "recording_limit"
    }
}
