package dev.chaseallbright.localscribe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingLimitTest {

    @Test
    fun `default is two minutes`() {
        assertEquals(RecordingLimit.TWO, RecordingLimit.DEFAULT)
        assertEquals(2, RecordingLimit.DEFAULT.minutes)
    }

    @Test
    fun `two minutes is the exact pcm byte count`() {
        // 16000 samples/sec * 2 bytes/sample * 60 sec * 2 min
        assertEquals(3_840_000L, RecordingLimit.TWO.bytes)
    }

    @Test
    fun `ten minutes is the exact pcm byte count`() {
        assertEquals(19_200_000L, RecordingLimit.TEN.bytes)
    }

    @Test
    fun `short limits need no confirmation`() {
        assertFalse(RecordingLimit.ONE.requiresConfirmation)
        assertFalse(RecordingLimit.TWO.requiresConfirmation)
        assertFalse(RecordingLimit.THREE.requiresConfirmation)
    }

    @Test
    fun `five minutes and above need confirmation`() {
        assertTrue(RecordingLimit.FIVE.requiresConfirmation)
        assertTrue(RecordingLimit.TEN.requiresConfirmation)
    }

    @Test
    fun `notches ascend so slider position maps to duration`() {
        val minutes = RecordingLimit.entries.map { it.minutes }
        assertEquals(minutes.sorted(), minutes)
    }

    @Test
    fun `ids are unique so persistence cannot collide`() {
        val ids = RecordingLimit.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `one minute is singular and the rest are plural`() {
        assertEquals("1 minute", RecordingLimit.ONE.displayName)
        assertEquals("2 minutes", RecordingLimit.TWO.displayName)
        assertEquals("10 minutes", RecordingLimit.TEN.displayName)
    }
}
