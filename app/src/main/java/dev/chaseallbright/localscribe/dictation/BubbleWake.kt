package dev.chaseallbright.localscribe.dictation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Counts the moments that should restart the bubble's collapse timer: a field gaining focus,
 * and a dictation finishing.
 *
 * [DictationUiState] cannot carry this. Moving from one field to another is Idle -> Idle, and a
 * StateFlow does not re-emit an equal value, so the second field would never restart the timer.
 * A counter always changes.
 */
class BubbleWake {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    fun onFieldFocused() = _count.update { it + 1 }

    fun onTransition(from: DictationUiState, to: DictationUiState) {
        if (finishesDictation(from, to)) _count.update { it + 1 }
    }

    companion object {
        /**
         * Leaving recording or processing for anything else is a finish -- inserted, cancelled
         * or failed alike. One rule here rather than a call at each of those sites, which could
         * drift. A refusal never entered recording, so it does not count.
         */
        fun finishesDictation(from: DictationUiState, to: DictationUiState): Boolean =
            from.isActive() && !to.isActive()

        private fun DictationUiState.isActive(): Boolean =
            this == DictationUiState.Recording || this == DictationUiState.Processing
    }
}
