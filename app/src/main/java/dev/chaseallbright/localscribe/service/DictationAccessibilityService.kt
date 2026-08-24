package dev.chaseallbright.localscribe.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import dev.chaseallbright.localscribe.dictation.DictationController
import dev.chaseallbright.localscribe.dictation.DictationUiState
import dev.chaseallbright.localscribe.dictation.TextInsertion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The only component with a live [AccessibilityNodeInfo] reference to the focused field, so
 * it both decides when the overlay should be visible and performs the final text insertion.
 */
class DictationAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private var focusedEditableNode: AccessibilityNodeInfo? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        ContextCompat.startForegroundService(this, Intent(this, OverlayBubbleService::class.java))

        serviceScope.launch {
            DictationController.transcriptReady.collect { transcript ->
                TextInsertion.insert(this@DictationAccessibilityService, focusedEditableNode, transcript.cleaned)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> updateFocusFromEvent(event)
        }
    }

    private fun updateFocusFromEvent(event: AccessibilityEvent) {
        val source = event.source

        // Don't show the dictation bubble over our own app's UI (onboarding, settings, etc.).
        if (source == null || source.packageName == packageName) {
            clearFocus()
            return
        }

        if (source.isEditable) {
            focusedEditableNode = source
            if (DictationController.state.value == DictationUiState.Hidden) {
                DictationController.setState(DictationUiState.Idle)
            }
        } else {
            clearFocus()
        }
    }

    private fun clearFocus() {
        focusedEditableNode = null
        if (DictationController.state.value == DictationUiState.Idle) {
            DictationController.setState(DictationUiState.Hidden)
        }
    }

    override fun onInterrupt() {
        clearFocus()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        serviceScope.cancel()
        DictationController.setState(DictationUiState.Hidden)
        return super.onUnbind(intent)
    }
}
