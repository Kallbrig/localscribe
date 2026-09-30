package dev.chaseallbright.localscribe.dictation

import android.util.Log
import dev.chaseallbright.localscribe.domain.Transcript
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

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
        // Atomic: setState is called from both the main thread and Dispatchers.Default, and the
        // wake rule must see the transition that actually happened.
        val previous = _state.getAndUpdate { newState }
        Log.d("DictationController", "state $previous -> $newState")
        wake.onTransition(previous, newState)
    }

    private val _fieldFocused = MutableStateFlow(false)

    /** Whether an editable field in another app is focused -- lets "Show bubble" act at once. */
    val fieldFocused: StateFlow<Boolean> = _fieldFocused.asStateFlow()

    fun setFieldFocused(focused: Boolean) {
        _fieldFocused.value = focused
    }

    private val _recordingIsHold = MutableStateFlow(false)

    /**
     * Whether the current recording was started by press-and-hold. Set by the recording service
     * when it starts, so it travels with the recording rather than being inferred by the overlay.
     */
    val recordingIsHold: StateFlow<Boolean> = _recordingIsHold.asStateFlow()

    fun setRecordingIsHold(hold: Boolean) {
        _recordingIsHold.value = hold
    }

    private val _imeTop = MutableStateFlow<Int?>(null)

    /**
     * Top edge of the on-screen keyboard in screen pixels, or null when none is showing.
     * Application overlays are drawn below the keyboard, so the overlay keeps clear of it.
     */
    val imeTop: StateFlow<Int?> = _imeTop.asStateFlow()

    fun setImeTop(top: Int?) {
        _imeTop.value = top
    }

    private val _starPromptRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when the GitHub star card is due; the overlay service draws it. */
    val starPromptRequests: SharedFlow<Unit> = _starPromptRequests.asSharedFlow()

    fun requestStarPrompt() {
        _starPromptRequests.tryEmit(Unit)
    }

    /** An editable field gained focus -- including a second field while already Idle. */
    fun onFieldFocused() = wake.onFieldFocused()

    suspend fun publishTranscript(transcript: Transcript) {
        _transcriptReady.emit(transcript)
    }
}
