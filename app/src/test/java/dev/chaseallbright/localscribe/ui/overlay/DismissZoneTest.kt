package dev.chaseallbright.localscribe.ui.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DismissZoneTest {

    @Test
    fun `centre is over`() {
        assertTrue(DismissZone.isOver(500f, 2000f, 500f, 2000f, radiusPx = 150f))
    }

    @Test
    fun `just inside the radius is over`() {
        assertTrue(DismissZone.isOver(500f, 2149f, 500f, 2000f, radiusPx = 150f))
    }

    @Test
    fun `on the radius is over`() {
        assertTrue(DismissZone.isOver(590f, 2120f, 500f, 2000f, radiusPx = 150f))
    }

    @Test
    fun `outside the radius diagonally is not over`() {
        // 110 and 110 on each axis is ~155 away: inside the bounding box, outside the circle.
        assertFalse(DismissZone.isOver(610f, 2110f, 500f, 2000f, radiusPx = 150f))
    }

    @Test
    fun `far away is not over`() {
        assertFalse(DismissZone.isOver(100f, 300f, 500f, 2000f, radiusPx = 150f))
    }
}
