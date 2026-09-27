package dev.chaseallbright.localscribe.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleOpacityTest {

    @Test
    fun `default is fully opaque`() {
        assertEquals(100, BubbleOpacity.DEFAULT_PERCENT)
    }

    @Test
    fun `snap never goes below the floor`() {
        assertEquals(15, BubbleOpacity.snap(0))
        assertEquals(15, BubbleOpacity.snap(-40))
        assertEquals(15, BubbleOpacity.snap(14))
    }

    @Test
    fun `snap never goes above fully opaque`() {
        assertEquals(100, BubbleOpacity.snap(250))
    }

    @Test
    fun `snap rounds to the nearest step`() {
        assertEquals(50, BubbleOpacity.snap(52))
        assertEquals(55, BubbleOpacity.snap(53))
        assertEquals(20, BubbleOpacity.snap(18))
    }

    @Test
    fun `slider index round trips every step`() {
        assertEquals(18, BubbleOpacity.STEP_COUNT)
        for (index in 0 until BubbleOpacity.STEP_COUNT) {
            val percent = BubbleOpacity.percentAt(index)
            assertEquals(index, BubbleOpacity.indexOf(percent))
        }
        assertEquals(15, BubbleOpacity.percentAt(0))
        assertEquals(100, BubbleOpacity.percentAt(BubbleOpacity.STEP_COUNT - 1))
    }

    @Test
    fun `alpha is the fraction`() {
        assertEquals(0.15f, BubbleOpacity.alphaOf(15), 0.0001f)
        assertEquals(1f, BubbleOpacity.alphaOf(100), 0.0001f)
    }
}
