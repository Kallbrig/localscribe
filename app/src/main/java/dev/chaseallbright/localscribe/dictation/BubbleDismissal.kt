package dev.chaseallbright.localscribe.dictation

import dev.chaseallbright.localscribe.settings.AppPreferences
import dev.chaseallbright.localscribe.ui.overlay.DismissDuration

/**
 * Hiding the bubble on request -- a drop on the X, or "Hide bubble" in the notification -- and
 * bringing it back. Both entry points go through here so they cannot disagree about what a
 * dismissal means.
 */
object BubbleDismissal {

    /** Hides the bubble now, and for the configured duration if one is set. */
    fun dismiss(preferences: AppPreferences, nowMillis: Long = System.currentTimeMillis()) {
        preferences.dismissedUntil = preferences.dismissDuration.deadlineFrom(nowMillis) ?: 0L
        val state = DictationController.state.value
        // Never hide a live recording or a dictation in flight: the pill is how it is confirmed.
        if (state == DictationUiState.Idle || state is DictationUiState.Error) {
            DictationController.setState(DictationUiState.Hidden)
        }
    }

    /** Ends any dismissal and, when a field is focused, shows the bubble straight away. */
    fun restore(preferences: AppPreferences) {
        preferences.dismissedUntil = 0L
        if (DictationController.fieldFocused.value && DictationController.state.value == DictationUiState.Hidden) {
            DictationController.onFieldFocused()
            DictationController.setState(DictationUiState.Idle)
        }
    }

    fun isSuppressed(preferences: AppPreferences, nowMillis: Long = System.currentTimeMillis()): Boolean =
        DismissDuration.isSuppressed(nowMillis, preferences.dismissedUntil)
}
