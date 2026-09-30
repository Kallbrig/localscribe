package dev.chaseallbright.localscribe.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DismissDurationTest {

    private val now = 1_000_000_000L

    @Test
    fun `default is until the next field`() {
        assertEquals(DismissDuration.UNTIL_NEXT_FIELD, DismissDuration.DEFAULT)
    }

    @Test
    fun `notches are the agreed set`() {
        assertEquals(listOf(null, 1, 5, 15, 30, 60), DismissDuration.entries.map { it.minutes })
    }

    @Test
    fun `until next field sets no deadline`() {
        assertNull(DismissDuration.UNTIL_NEXT_FIELD.deadlineFrom(now))
    }

    @Test
    fun `a timed choice sets a deadline that far ahead`() {
        assertEquals(now + 5 * 60_000L, DismissDuration.FIVE.deadlineFrom(now))
    }

    @Test
    fun `suppressed before the deadline and not after`() {
        val deadline = now + 60_000L
        assertTrue(DismissDuration.isSuppressed(now, deadline))
        assertFalse(DismissDuration.isSuppressed(deadline, deadline))
        assertFalse(DismissDuration.isSuppressed(deadline + 1, deadline))
    }

    @Test
    fun `no deadline means not suppressed`() {
        assertFalse(DismissDuration.isSuppressed(now, 0L))
    }

    @Test
    fun `a deadline beyond the longest option is treated as expired`() {
        // The clock was moved back: an hour's dismissal must not become a day's.
        assertFalse(DismissDuration.isSuppressed(now, now + 2 * 60 * 60_000L))
        assertTrue(DismissDuration.isSuppressed(now, now + 60 * 60_000L))
    }

    @Test
    fun `ids are unique so persistence cannot collide`() {
        val ids = DismissDuration.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `display names`() {
        assertEquals("Until next text field", DismissDuration.UNTIL_NEXT_FIELD.displayName)
        assertEquals("1 minute", DismissDuration.ONE.displayName)
        assertEquals("15 minutes", DismissDuration.FIFTEEN.displayName)
        assertEquals("1 hour", DismissDuration.SIXTY.displayName)
    }
}
