package dev.chaseallbright.localscribe.dictation

/** A field's new contents after dictation, plus where the cursor should land. */
data class SplicedText(val text: String, val cursor: Int)

/** Characters that already "open" a gap, so dictation should butt straight up against them. */
private val OPENING = setOf('(', '[', '{', '<', '"', '\'', '“', '‘', '¿', '¡')

/** Characters that attach to the preceding word, so no space belongs in front of them. */
private val ATTACHING = setOf(
    ',', '.', '!', '?', ';', ':', ')', ']', '}', '>', '"', '\'', '”', '’', '%'
)

/**
 * Splices dictated text into a field's existing contents at the cursor, adding separating
 * spaces the way a person typing would.
 *
 * Needed because `ACTION_SET_TEXT` replaces a node's entire contents, which would wipe
 * anything the user had already typed. Selection indices come straight from
 * `AccessibilityNodeInfo`, so they may be absent (-1 when the node reports no selection
 * information), reversed, or stale relative to the text -- all handled here rather than at
 * the call site.
 *
 * Spacing is deliberately conservative: a space is added only where its absence would run
 * two words together, never next to existing whitespace, an opening bracket or quote, or
 * punctuation that belongs tight against its neighbour.
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

    val before = existing.substring(0, start)
    val after = existing.substring(end)

    val leadingSpace = insertion.firstOrNull()?.let { first ->
        val previous = before.lastOrNull()
        previous != null && !previous.isWhitespace() && previous !in OPENING &&
            !first.isWhitespace() && first !in ATTACHING
    } == true

    val trailingSpace = insertion.lastOrNull()?.let { last ->
        val next = after.firstOrNull()
        next != null && !next.isWhitespace() && next !in ATTACHING && !last.isWhitespace()
    } == true

    val prefix = if (leadingSpace) " " else ""
    val suffix = if (trailingSpace) " " else ""

    return SplicedText(
        text = before + prefix + insertion + suffix + after,
        // Sits directly after the dictation, before any separator we appended.
        cursor = start + prefix.length + insertion.length
    )
}
