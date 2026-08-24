package dev.chaseallbright.localscribe.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import dev.chaseallbright.localscribe.R
import dev.chaseallbright.localscribe.dictation.DictationController
import dev.chaseallbright.localscribe.dictation.DictationUiState
import dev.chaseallbright.localscribe.dictation.ModelSession
import dev.chaseallbright.localscribe.dictation.TextInsertion
import dev.chaseallbright.localscribe.domain.CleanupBackend
import dev.chaseallbright.localscribe.domain.Transcript
import dev.chaseallbright.localscribe.models.ModelManager
import dev.chaseallbright.localscribe.settings.AppPreferences
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
                maybeToastCleanupFallback(transcript)
            }
        }
    }

    /**
     * The user chose LLM cleanup by installing a model; tell them when they silently got
     * the rules cleaner instead (LLM output rejected, or the model failed to load).
     */
    private fun maybeToastCleanupFallback(transcript: Transcript) {
        // ModelManager.isCleanupModelReady is a single File.isFile stat and AppPreferences reads
        // SharedPreferences -- both synchronous, but this fires once per completed dictation on
        // the main thread, so a coroutine hop here isn't worth the added complexity.
        val fellBack = when (transcript.backend) {
            CleanupBackend.RULES_FALLBACK -> true
            CleanupBackend.RULES ->
                ModelManager(this).isCleanupModelReady(AppPreferences(this).cleanupTier)
            else -> false
        }
        if (fellBack) {
            Toast.makeText(this, getString(R.string.cleanup_fallback_toast), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            // Window-content-changed fires for arbitrary subtree changes -- its source is
            // often a parent container, not the focused view, so it can't be used to track
            // focus (it was overwriting a correct Idle transition with a spurious Hidden one).
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> updateFocusFromEvent(event)
            // Fires for the IME's own window opening/closing too, not just real app switches
            // -- only treat it as "left the app" if the foreground *application* window
            // (as opposed to the keyboard's TYPE_INPUT_METHOD window) actually changed.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (activeApplicationPackage() != focusedEditableNode?.packageName) {
                    clearFocus()
                }
            }
        }
    }

    private fun activeApplicationPackage(): CharSequence? =
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?.root?.packageName

    private fun updateFocusFromEvent(event: AccessibilityEvent) {
        val source = event.source

        // Don't show the dictation bubble over our own app's UI (onboarding, settings, etc.).
        if (source == null || source.packageName == packageName) {
            clearFocus()
            return
        }

        if (source.isEditable) {
            ModelSession.prewarm(this)
            focusedEditableNode = source
            if (DictationController.state.value == DictationUiState.Hidden) {
                DictationController.setState(DictationUiState.Idle)
            }
        } else {
            clearFocus()
        }
    }

    private fun clearFocus() {
        ModelSession.onFocusLost()
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
