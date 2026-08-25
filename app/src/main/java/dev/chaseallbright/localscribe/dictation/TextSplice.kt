package dev.chaseallbright.localscribe.dictation

/** A field's new contents after dictation, plus where the cursor should land. */
data class SplicedText(val text: String, val cursor: Int)

/**
 * Splices dictated text into a field's existing contents at the cursor.
 *
 * Needed because `ACTION_SET_TEXT` replaces a node's entire contents, which would wipe
 * anything the user had already typed. Selection indices come straight from
 * `AccessibilityNodeInfo`, so they may be absent (-1 when the node reports no selection
 * information), reversed, or stale relative to the text -- all handled here rather than at
 * the call site.
 */
fun spliceAtCursor(
    existing: String,
    selectionStart: Int,
    selectionEnd: Int,
    insertion: String
): SplicedText {
    // With no selection information, append: overwriting the user's text would be worse.
    val known = selectionStart >= 0 && selectionEnd >= 0
    val rawStart = if (known) minOf(selectionStart, selectionEnd) else existing.length
    val rawEnd = if (known) maxOf(selectionStart, selectionEnd) else existing.length

    val start = rawStart.coerceIn(0, existing.length)
    val end = rawEnd.coerceIn(start, existing.length)

    return SplicedText(
        text = existing.substring(0, start) + insertion + existing.substring(end),
        cursor = start + insertion.length
    )
}
