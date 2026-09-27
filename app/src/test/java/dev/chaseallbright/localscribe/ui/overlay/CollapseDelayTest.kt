package dev.chaseallbright.localscribe.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollapseDelayTest {

    @Test
    fun `default is three seconds`() {
        assertEquals(CollapseDelay.THREE, CollapseDelay.DEFAULT)
        assertEquals(3_000L, CollapseDelay.DEFAULT.millis)
    }

    @Test
    fun `notches are the agreed set with never last`() {
        assertEquals(
            listOf(0, 1, 2, 3, 5, 10, 15, 30, 60, null),
            CollapseDelay.entries.map { it.seconds }
        )
    }

    @Test
    fun `never has no timer`() {
        assertNull(CollapseDelay.NEVER.millis)
    }

    @Test
    fun `zero collapses immediately`() {
        assertEquals(0L, CollapseDelay.ZERO.millis)
    }

    @Test
    fun `ids are unique so persistence cannot collide`() {
        val ids = CollapseDelay.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `display names read as durations`() {
        assertEquals("Immediately", CollapseDelay.ZERO.displayName)
        assertEquals("1 second", CollapseDelay.ONE.displayName)
        assertEquals("3 seconds", CollapseDelay.THREE.displayName)
        assertEquals("1 minute", CollapseDelay.SIXTY.displayName)
        assertEquals("Never", CollapseDelay.NEVER.displayName)
    }
}
