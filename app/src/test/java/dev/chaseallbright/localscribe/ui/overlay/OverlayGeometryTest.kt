package dev.chaseallbright.localscribe.ui.overlay

import dev.chaseallbright.localscribe.dictation.DictationUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayGeometryTest {

    // A 1080 x 2400 screen with a 100 px status band and a 150 px navigation band.
    private val bounds = Bounds(left = 40, top = 100, right = 1040, bottom = 2250)

    @Test
    fun `an anchor inside the bounds is left alone`() {
        assertEquals(PxPoint(500, 1000), OverlayGeometry.clampAnchor(500, 1000, 150, bounds))
    }

    @Test
    fun `an anchor is kept far enough in for the whole bubble`() {
        // Half of a 150 px bubble is 75, so its centre cannot come closer than that to an edge.
        assertEquals(PxPoint(115, 175), OverlayGeometry.clampAnchor(0, 0, 150, bounds))
        assertEquals(PxPoint(965, 2175), OverlayGeometry.clampAnchor(5000, 5000, 150, bounds))
    }

    @Test
    fun `a shape is centred on the anchor`() {
        assertEquals(PxPoint(425, 925), OverlayGeometry.placeCentered(500, 1000, 150, 150, bounds))
    }

    @Test
    fun `a pill at the right edge slides inward instead of leaving the screen`() {
        // Bubble hard against the right bound; a 330 px pill centred on it would overhang.
        val anchor = OverlayGeometry.clampAnchor(5000, 1000, 150, bounds)
        val topLeft = OverlayGeometry.placeCentered(anchor.x, anchor.y, 330, 150, bounds)
        assertEquals(1040 - 330, topLeft.x)
        assertEquals(925, topLeft.y)
    }

    @Test
    fun `a pill at the left edge slides inward`() {
        val anchor = OverlayGeometry.clampAnchor(0, 1000, 150, bounds)
        assertEquals(40, OverlayGeometry.placeCentered(anchor.x, anchor.y, 330, 150, bounds).x)
    }

    @Test
    fun `a pill near the top stays below the status band`() {
        val anchor = OverlayGeometry.clampAnchor(500, 0, 150, bounds)
        assertEquals(100, OverlayGeometry.placeCentered(anchor.x, anchor.y, 330, 150, bounds).y)
    }

    @Test
    fun `a shape wider than the bounds is centred within them`() {
        assertEquals(40 + (1000 - 1200) / 2, OverlayGeometry.placeCentered(500, 1000, 1200, 150, bounds).x)
    }

    @Test
    fun `shapes follow state`() {
        assertEquals(OverlayShape.NONE, OverlayShape.of(DictationUiState.Hidden, showingDot = false, holding = false))
        assertEquals(OverlayShape.BUBBLE, OverlayShape.of(DictationUiState.Idle, showingDot = false, holding = false))
        assertEquals(OverlayShape.DOT, OverlayShape.of(DictationUiState.Idle, showingDot = true, holding = false))
        assertEquals(OverlayShape.BUBBLE, OverlayShape.of(DictationUiState.Error("x"), showingDot = false, holding = false))
        assertEquals(OverlayShape.PILL, OverlayShape.of(DictationUiState.Recording, showingDot = false, holding = false))
        assertEquals(OverlayShape.HOLD_PILL, OverlayShape.of(DictationUiState.Recording, showingDot = false, holding = true))
        assertEquals(OverlayShape.PROCESSING, OverlayShape.of(DictationUiState.Processing, showingDot = false, holding = false))
    }

    @Test
    fun `a hold that has not started recording yet still shows the bubble`() {
        assertEquals(OverlayShape.BUBBLE, OverlayShape.of(DictationUiState.Idle, showingDot = false, holding = true))
    }

    @Test
    fun `the hold pill is the size of the tap pill`() {
        assertEquals(OverlayShape.PILL.widthDp, OverlayShape.HOLD_PILL.widthDp)
        assertEquals(OverlayShape.PILL.heightDp, OverlayShape.HOLD_PILL.heightDp)
    }
}
