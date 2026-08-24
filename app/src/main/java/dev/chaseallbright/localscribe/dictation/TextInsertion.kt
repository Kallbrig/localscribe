package dev.chaseallbright.localscribe.dictation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Three-tier text insertion, in order of preference. Never silently loses the transcript --
 * worst case it always ends up on the clipboard.
 */
object TextInsertion {
    private const val CLIP_LABEL = "LocalScribe dictation"

    fun insert(context: Context, node: AccessibilityNodeInfo?, text: String) {
        if (node != null && trySetText(node, text)) return
        if (node != null && tryClipboardPaste(context, node, text)) return
        copyToClipboardOnly(context, text)
    }

    private fun trySetText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.refresh() || !node.isEditable) return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun tryClipboardPaste(context: Context, node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.refresh() || !node.isEditable) return false
        setClipboard(context, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun copyToClipboardOnly(context: Context, text: String) {
        setClipboard(context, text)
        Toast.makeText(context, "Copied — paste manually", Toast.LENGTH_SHORT).show()
    }

    private fun setClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
    }
}
