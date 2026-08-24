package dev.chaseallbright.localscribe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These drive the decision QwenCleaner uses to fall back to RuleBasedCleaner when the LLM
 * output isn't trustworthy -- e.g. when a small model answers a dictated question instead
 * of just cleaning it up.
 */
class TextCleanupUtilsTest {

    @Test
    fun `faithful edit that only fixes grammar is accepted`() {
        assertTrue(TextCleanupUtils.isFaithful("i think we should go", "I think we should go."))
    }

    @Test
    fun `conversational reply to a dictated question is rejected`() {
        assertFalse(TextCleanupUtils.isFaithful("Hi, how are you?", "I'm fine, how about you?"))
    }

    @Test
    fun `dropping a question mark from a dictated question is rejected`() {
        assertFalse(TextCleanupUtils.isFaithful("Are you coming to the meeting?", "You are coming to the meeting."))
    }

    @Test
    fun `edit that introduces substantial new content is rejected`() {
        assertFalse(
            TextCleanupUtils.isFaithful(
                "the sky is blue",
                "the sky is blue because of Rayleigh scattering of sunlight in the atmosphere"
            )
        )
    }

    @Test
    fun `restoreWords fixes casing without needing exact whisper output`() {
        val result = TextCleanupUtils.restoreWords("please call kallbrig and dana", listOf("Kallbrig", "Dana"))
        assertEquals("please call Kallbrig and Dana", result)
    }
}
