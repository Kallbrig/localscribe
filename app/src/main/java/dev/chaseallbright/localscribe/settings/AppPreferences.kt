package dev.chaseallbright.localscribe.settings

import android.content.Context
import dev.chaseallbright.localscribe.audio.RecordingLimit
import dev.chaseallbright.localscribe.domain.CleanupMode
import dev.chaseallbright.localscribe.models.CleanupModelTier
import dev.chaseallbright.localscribe.models.ModelManager
import dev.chaseallbright.localscribe.models.WhisperModelTier
import dev.chaseallbright.localscribe.ui.overlay.BubbleColor
import dev.chaseallbright.localscribe.ui.overlay.BubbleOpacity
import dev.chaseallbright.localscribe.ui.overlay.BubbleStyle
import dev.chaseallbright.localscribe.ui.overlay.CollapseDelay

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

    var collapseDelay: CollapseDelay
        get() = prefs.getString(KEY_COLLAPSE_DELAY, null)
            ?.let { id -> CollapseDelay.entries.find { it.id == id } }
            ?: CollapseDelay.DEFAULT
        set(value) = prefs.edit().putString(KEY_COLLAPSE_DELAY, value.id).apply()

    var bubbleColor: BubbleColor
        get() = prefs.getString(KEY_BUBBLE_COLOR, null)
            ?.let { id -> BubbleColor.entries.find { it.id == id } }
            ?: BubbleColor.DEFAULT
        set(value) = prefs.edit().putString(KEY_BUBBLE_COLOR, value.id).apply()

    var bubbleOpacityPercent: Int
        get() = BubbleOpacity.snap(prefs.getInt(KEY_BUBBLE_OPACITY, BubbleOpacity.DEFAULT_PERCENT))
        set(value) = prefs.edit().putInt(KEY_BUBBLE_OPACITY, BubbleOpacity.snap(value)).apply()

    val bubbleStyle: BubbleStyle
        get() = BubbleStyle(bubbleColor, bubbleOpacityPercent)

    /**
     * Calls [onChange] whenever a bubble setting changes, so the overlay picks up edits made in
     * Settings without restarting. Returns the unregister call. SharedPreferences holds listeners
     * weakly, so the caller must keep the returned function (which holds the listener) alive.
     */
    fun observeBubbleSettings(onChange: () -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in BUBBLE_KEYS) onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val PREFS_NAME = "localscribe_settings"
        const val KEY_WHISPER_TIER = "whisper_tier"
        const val KEY_CLEANUP_TIER = "cleanup_tier"
        const val KEY_CLEANUP_MODE = "cleanup_mode"
        const val KEY_RECORDING_LIMIT = "recording_limit"
        const val KEY_COLLAPSE_DELAY = "bubble_collapse_delay"
        const val KEY_BUBBLE_COLOR = "bubble_color"
        const val KEY_BUBBLE_OPACITY = "bubble_opacity_percent"
        val BUBBLE_KEYS = setOf(KEY_COLLAPSE_DELAY, KEY_BUBBLE_COLOR, KEY_BUBBLE_OPACITY)
    }
}
