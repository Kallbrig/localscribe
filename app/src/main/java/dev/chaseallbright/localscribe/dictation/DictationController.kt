package dev.chaseallbright.localscribe.dictation

import android.util.Log
import dev.chaseallbright.localscribe.domain.Transcript
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface DictationUiState {
    /** No editable field focused anywhere -- overlay fully hidden. */
    data object Hidden : DictationUiState

    /** An editable field is focused; idle bubble shown, not recording. */
    data object Idle : DictationUiState

    data object Recording : DictationUiState

    data object Processing : DictationUiState

    data class Error(val message: String) : DictationUiState
}

/**
 * In-process coordination point between DictationAccessibilityService (focus tracking +
 * text insertion), OverlayBubbleService (UI/gestures), and DictationForegroundService
 * (recording + pipeline). All three run in the same process, so a shared observable state
 * holder is simpler and more robust here than Binder/Messenger IPC between them.
 */
object DictationController {
    private val _state = MutableStateFlow<DictationUiState>(DictationUiState.Hidden)
    val state: StateFlow<DictationUiState> = _state.asStateFlow()

    private val _transcriptReady = MutableSharedFlow<Transcript>(extraBufferCapacity = 1)
    val transcriptReady: SharedFlow<Transcript> = _transcriptReady.asSharedFlow()

    private val wake = BubbleWake()

    /** Bumps whenever the bubble's collapse timer should restart. See [BubbleWake]. */
    val bubbleWake: StateFlow<Int> = wake.count

    fun setState(newState: DictationUiState) {
        val previous = _state.value
        Log.d("DictationController", "state $previous -> $newState")
        _state.value = newState
        wake.onTransition(previous, newState)
    }

    /** An editable field gained focus -- including a second field while already Idle. */
    fun onFieldFocused() = wake.onFieldFocused()

    suspend fun publishTranscript(transcript: Transcript) {
        _transcriptReady.emit(transcript)
    }
}
