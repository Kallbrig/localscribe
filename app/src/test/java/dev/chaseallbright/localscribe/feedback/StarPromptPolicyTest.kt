package dev.chaseallbright.localscribe.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarPromptPolicyTest {

    private fun StarPromptPolicy.uses(n: Int): StarPromptPolicy =
        (1..n).fold(this) { state, _ -> state.recordUse() }

    @Test
    fun `not due before the hundredth use`() {
        assertFalse(StarPromptPolicy().uses(99).due)
    }

    @Test
    fun `due on the hundredth use`() {
        assertTrue(StarPromptPolicy().uses(100).due)
    }

    @Test
    fun `remind me later waits another hundred`() {
        val later = StarPromptPolicy().uses(100).remindLater()
        assertFalse(later.due)
        assertFalse(later.uses(99).due)
        assertTrue(later.uses(100).due)
    }

    @Test
    fun `finishing ends it for good`() {
        // Both "Take me there" and "Don't remind me" finish.
        val done = StarPromptPolicy().uses(100).finish()
        assertFalse(done.uses(10_000).due)
    }

    @Test
    fun `finished stops counting`() {
        // Nothing reads the count once finished; not changing it saves a preference write per
        // dictation for the rest of the install's life.
        val done = StarPromptPolicy().uses(100).finish()
        assertEquals(done, done.recordUse())
    }

    @Test
    fun `a card left unanswered stays due`() {
        // If the process dies with the card on screen, the next dictation asks again.
        assertTrue(StarPromptPolicy().uses(101).due)
    }
}
