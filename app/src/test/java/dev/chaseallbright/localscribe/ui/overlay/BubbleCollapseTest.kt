package dev.chaseallbright.localscribe.ui.overlay

import dev.chaseallbright.localscribe.ui.overlay.BubbleCollapse.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleCollapseTest {

    private fun BubbleCollapse.after(vararg events: Event): BubbleCollapse =
        events.fold(this) { state, event -> state.reduce(event) }

    @Test
    fun `starts expanded`() {
        assertFalse(BubbleCollapse().collapsed)
    }

    @Test
    fun `expiry of the current timer collapses`() {
        val woken = BubbleCollapse().reduce(Event.Wake)
        assertTrue(woken.reduce(Event.TimerExpired(woken.generation)).collapsed)
    }

    @Test
    fun `wake expands and restarts the timer`() {
        val collapsed = BubbleCollapse().after(Event.Wake).let { it.reduce(Event.TimerExpired(it.generation)) }
        val rewoken = collapsed.reduce(Event.Wake)
        assertFalse(rewoken.collapsed)
        assertEquals(collapsed.generation + 1, rewoken.generation)
    }

    @Test
    fun `expand behaves as a wake`() {
        val collapsed = BubbleCollapse().after(Event.Wake).let { it.reduce(Event.TimerExpired(it.generation)) }
        val expanded = collapsed.reduce(Event.Expand)
        assertFalse(expanded.collapsed)
        assertEquals(collapsed.generation + 1, expanded.generation)
    }

    @Test
    fun `an expiry from a restarted timer is ignored`() {
        val first = BubbleCollapse().reduce(Event.Wake)
        val second = first.reduce(Event.Wake)
        assertFalse(second.reduce(Event.TimerExpired(first.generation)).collapsed)
    }

    @Test
    fun `dragging does not restart the timer`() {
        val woken = BubbleCollapse().reduce(Event.Wake)
        val dragged = woken.after(Event.DragStart, Event.DragEnd)
        assertEquals(woken.generation, dragged.generation)
        assertTrue(dragged.reduce(Event.TimerExpired(woken.generation)).collapsed)
    }

    @Test
    fun `expiry mid-drag waits for the release`() {
        val woken = BubbleCollapse().reduce(Event.Wake)
        val midDrag = woken.after(Event.DragStart, Event.TimerExpired(woken.generation))
        assertFalse(midDrag.collapsed)
        assertTrue(midDrag.reduce(Event.DragEnd).collapsed)
    }

    @Test
    fun `a wake mid-drag cancels the deferred collapse`() {
        val woken = BubbleCollapse().reduce(Event.Wake)
        val state = woken.after(
            Event.DragStart,
            Event.TimerExpired(woken.generation),
            Event.Wake,
            Event.DragEnd
        )
        assertFalse(state.collapsed)
    }

    @Test
    fun `a release with nothing deferred leaves the bubble expanded`() {
        assertFalse(BubbleCollapse().after(Event.Wake, Event.DragStart, Event.DragEnd).collapsed)
    }

    @Test
    fun `a wake uses the configured delay as is`() {
        val woken = BubbleCollapse().reduce(Event.Wake)
        assertEquals(0L, woken.timerMillis(CollapseDelay.ZERO))
        assertEquals(10_000L, woken.timerMillis(CollapseDelay.TEN))
    }

    @Test
    fun `a tap on the dot gets a grace period so the bubble can be reached`() {
        // At "Immediately" the expanded bubble would otherwise re-collapse before the user
        // could tap it, and dictation would be unreachable.
        val expanded = BubbleCollapse().reduce(Event.Expand)
        assertEquals(BubbleCollapse.TAP_GRACE_MS, expanded.timerMillis(CollapseDelay.ZERO))
        assertEquals(BubbleCollapse.TAP_GRACE_MS, expanded.timerMillis(CollapseDelay.ONE))
        assertEquals(10_000L, expanded.timerMillis(CollapseDelay.TEN))
    }

    @Test
    fun `a later wake drops the grace period`() {
        val state = BubbleCollapse().after(Event.Expand, Event.Wake)
        assertEquals(0L, state.timerMillis(CollapseDelay.ZERO))
    }

    @Test
    fun `never has no timer even after a tap`() {
        assertEquals(null, BubbleCollapse().reduce(Event.Expand).timerMillis(CollapseDelay.NEVER))
    }
}
