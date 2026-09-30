package dev.chaseallbright.localscribe.ui.overlay

import dev.chaseallbright.localscribe.dictation.DictationUiState

/** A point in screen pixels. */
data class PxPoint(val x: Int, val y: Int)

/**
 * The area overlay shapes must stay inside, in screen pixels: in from the sides, below the status
 * bar (where a swipe opens the notification shade) and above the navigation bar or gesture area.
 */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Where overlay shapes go. The bubble's centre -- the anchor -- is the only stored position;
 * every shape is centred on it and then pushed back inside [Bounds], so a pill that would
 * overhang an edge slides inward rather than leaving the screen, and the bubble is back where it
 * was once the pill goes away.
 */
object OverlayGeometry {

    /** Keeps a bubble of [sizePx] centred on the anchor fully inside [bounds]. */
    fun clampAnchor(x: Int, y: Int, sizePx: Int, bounds: Bounds): PxPoint {
        val half = sizePx / 2
        return PxPoint(
            x = clampCentre(x, half, bounds.left, bounds.right),
            y = clampCentre(y, half, bounds.top, bounds.bottom)
        )
    }

    /** Top-left for a [width] x [height] shape centred on the anchor, then kept inside [bounds]. */
    fun placeCentered(anchorX: Int, anchorY: Int, width: Int, height: Int, bounds: Bounds): PxPoint =
        PxPoint(
            x = clampStart(anchorX - width / 2, width, bounds.left, bounds.right),
            y = clampStart(anchorY - height / 2, height, bounds.top, bounds.bottom)
        )

    private fun clampCentre(centre: Int, half: Int, low: Int, high: Int): Int =
        if (high - low < half * 2) (low + high) / 2 else centre.coerceIn(low + half, high - half)

    /** A shape that cannot fit is centred in the bounds rather than pinned to one side. */
    private fun clampStart(start: Int, size: Int, low: Int, high: Int): Int =
        if (high - low < size) low + (high - low - size) / 2 else start.coerceIn(low, high - size)
}

/** What the overlay is drawing, with the size of its visible content. */
enum class OverlayShape(val widthDp: Int, val heightDp: Int) {
    NONE(0, 0),
    DOT(28, 28),
    BUBBLE(56, 56),
    PILL(120, 56),
    HOLD_PILL(120, 56),
    PROCESSING(56, 56);

    companion object {
        /** [holding] is whether the recording was started by press-and-hold. */
        fun of(state: DictationUiState, showingDot: Boolean, holding: Boolean): OverlayShape = when (state) {
            DictationUiState.Hidden -> NONE
            DictationUiState.Idle, is DictationUiState.Error -> if (showingDot) DOT else BUBBLE
            DictationUiState.Recording -> if (holding) HOLD_PILL else PILL
            DictationUiState.Processing -> PROCESSING
        }
    }
}
