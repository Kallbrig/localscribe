package dev.chaseallbright.localscribe.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class DictationAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Focus tracking and overlay show/hide wired up in Phase 5.
    }

    override fun onInterrupt() {
        // No-op: nothing to cancel yet.
    }
}
