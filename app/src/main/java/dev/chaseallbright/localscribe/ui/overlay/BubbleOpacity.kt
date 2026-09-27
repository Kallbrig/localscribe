package dev.chaseallbright.localscribe.ui.overlay

import kotlin.math.roundToInt

/**
 * Opacity of the whole overlay, stored as a whole percentage.
 *
 * The floor is deliberate. A bubble faded to nothing looks exactly like a broken accessibility
 * service -- the same reason the bubble is kept, not hidden, on unsupported processors.
 */
object BubbleOpacity {
    const val MIN_PERCENT = 15
    const val MAX_PERCENT = 100
    const val STEP = 5
    const val DEFAULT_PERCENT = MAX_PERCENT

    /** Number of selectable positions, both ends included. */
    const val STEP_COUNT = (MAX_PERCENT - MIN_PERCENT) / STEP + 1

    /** Clamps into range and rounds to the nearest step. */
    fun snap(percent: Int): Int {
        val clamped = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
        val steps = ((clamped - MIN_PERCENT) / STEP.toFloat()).roundToInt()
        return MIN_PERCENT + steps * STEP
    }

    fun percentAt(index: Int): Int = snap(MIN_PERCENT + index * STEP)

    fun indexOf(percent: Int): Int = (snap(percent) - MIN_PERCENT) / STEP

    fun alphaOf(percent: Int): Float = snap(percent) / 100f
}
