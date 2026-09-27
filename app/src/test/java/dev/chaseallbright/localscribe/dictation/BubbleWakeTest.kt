package dev.chaseallbright.localscribe.dictation

import dev.chaseallbright.localscribe.dictation.DictationUiState.Error
import dev.chaseallbright.localscribe.dictation.DictationUiState.Hidden
import dev.chaseallbright.localscribe.dictation.DictationUiState.Idle
import dev.chaseallbright.localscribe.dictation.DictationUiState.Processing
import dev.chaseallbright.localscribe.dictation.DictationUiState.Recording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleWakeTest {

    @Test
    fun `a finished dictation wakes the bubble`() {
        assertTrue(BubbleWake.finishesDictation(Processing, Idle))
    }

    @Test
    fun `a cancelled recording wakes the bubble`() {
        assertTrue(BubbleWake.finishesDictation(Recording, Idle))
    }

    @Test
    fun `a failed dictation wakes the bubble`() {
        assertTrue(BubbleWake.finishesDictation(Processing, Error("x")))
    }

    @Test
    fun `stopping to process is not a finish`() {
        assertFalse(BubbleWake.finishesDictation(Recording, Processing))
    }

    @Test
    fun `a refusal never recorded so it does not wake`() {
        assertFalse(BubbleWake.finishesDictation(Idle, Error("x")))
        assertFalse(BubbleWake.finishesDictation(Error("x"), Idle))
    }

    @Test
    fun `focus transitions alone do not wake through state`() {
        assertFalse(BubbleWake.finishesDictation(Hidden, Idle))
        assertFalse(BubbleWake.finishesDictation(Idle, Hidden))
    }

    @Test
    fun `a finish bumps the wake counter`() {
        val wake = BubbleWake()
        wake.onTransition(Processing, Idle)
        assertEquals(1, wake.count.value)
    }

    @Test
    fun `a non-finish transition leaves the counter alone`() {
        val wake = BubbleWake()
        wake.onTransition(Recording, Processing)
        wake.onTransition(Idle, Error("x"))
        assertEquals(0, wake.count.value)
    }

    @Test
    fun `field focus bumps the counter every time, even when already idle`() {
        // Moving between two fields is Idle -> Idle, which StateFlow would conflate away; this
        // counter is what lets the second field still restart the collapse timer.
        val wake = BubbleWake()
        wake.onFieldFocused()
        wake.onFieldFocused()
        assertEquals(2, wake.count.value)
    }
}
