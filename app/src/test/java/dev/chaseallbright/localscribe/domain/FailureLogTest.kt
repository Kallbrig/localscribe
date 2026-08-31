package dev.chaseallbright.localscribe.domain

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FailureLogTest {

    @Before
    fun reset() = FailureLog.clear()

    @Test
    fun `starts empty`() {
        assertEquals(emptyList<String>(), FailureLog.recent())
    }

    @Test
    fun `newest is first`() {
        FailureLog.record("E-DICT/A")
        FailureLog.record("E-DICT/B")
        assertEquals(listOf("E-DICT/B", "E-DICT/A"), FailureLog.recent())
    }

    @Test
    fun `keeps only the last three`() {
        listOf("A", "B", "C", "D").forEach { FailureLog.record("E-DICT/$it") }
        assertEquals(
            listOf("E-DICT/D", "E-DICT/C", "E-DICT/B"),
            FailureLog.recent()
        )
    }

    @Test
    fun `clear empties it`() {
        FailureLog.record("E-DICT/A")
        FailureLog.clear()
        assertEquals(emptyList<String>(), FailureLog.recent())
    }
}
