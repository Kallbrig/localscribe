package dev.chaseallbright.localscribe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingBudgetTest {

    @Test
    fun `chunk well under the limit is accepted whole`() {
        val budget = RecordingBudget(1000L)
        assertEquals(400, budget.accept(400))
        assertEquals(400L, budget.usedBytes)
        assertFalse(budget.isFull)
    }

    @Test
    fun `chunk straddling the limit is truncated to the remainder`() {
        val budget = RecordingBudget(1000L)
        budget.accept(900)
        // 100 bytes of room left, 400 offered.
        assertEquals(100, budget.accept(400))
        assertEquals(1000L, budget.usedBytes)
        assertTrue(budget.isFull)
    }

    @Test
    fun `chunks after the limit are rejected entirely`() {
        val budget = RecordingBudget(1000L)
        budget.accept(1000)
        assertEquals(0, budget.accept(256))
        assertEquals(0, budget.accept(1))
        assertEquals(1000L, budget.usedBytes)
    }

    @Test
    fun `a chunk landing exactly on the limit fills without over-accepting`() {
        val budget = RecordingBudget(1000L)
        budget.accept(600)
        assertEquals(400, budget.accept(400))
        assertEquals(1000L, budget.usedBytes)
        assertTrue(budget.isFull)
    }

    @Test
    fun `a fresh budget is not full`() {
        assertFalse(RecordingBudget(1L).isFull)
    }

    @Test
    fun `non-positive reads are ignored`() {
        val budget = RecordingBudget(1000L)
        assertEquals(0, budget.accept(0))
        assertEquals(0, budget.accept(-1))
        assertEquals(0L, budget.usedBytes)
    }

    @Test
    fun `a real limit is far larger than one read and stays unfull`() {
        val budget = RecordingBudget(RecordingLimit.TWO.bytes)
        assertEquals(4096, budget.accept(4096))
        assertFalse(budget.isFull)
    }

    @Test
    fun `a non-positive limit is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { RecordingBudget(0L) }
        assertThrows(IllegalArgumentException::class.java) { RecordingBudget(-1L) }
    }
}
