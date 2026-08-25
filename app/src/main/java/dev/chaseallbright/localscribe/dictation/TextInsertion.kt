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

    /** Returns true when text landed directly in the field (SET_TEXT or clipboard+PASTE), false when it only reached the clipboard. */
    fun insert(context: Context, node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node != null && trySetText(node, text)) return true
        if (node != null && tryClipboardPaste(context, node, text)) return true
        copyToClipboardOnly(context, text)
        return false
    }

    private fun trySetText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.refresh() || !node.isEditable) return false

        // ACTION_SET_TEXT replaces the node's whole contents, so splice the dictation into
        // what's already there. When the field is empty its `text` is the placeholder hint,
        // which must not be treated as real content.
        val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val spliced = spliceAtCursor(existing, node.textSelectionStart, node.textSelectionEnd, text)

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, spliced.text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return false

        // Leave the cursor after the dictation; otherwise it jumps to the end of the field,
        // which is wrong whenever we inserted into the middle. Best-effort: the text landed
        // either way, so a field that refuses the selection still counts as a success.
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, spliced.cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, spliced.cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        return true
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
