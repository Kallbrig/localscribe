package dev.chaseallbright.localscribe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Informal and business used to produce near-identical text. The prompt was only half the
 * cause; the dominant one was [TextCleanupUtils.isFaithful] rejecting any output that
 * introduced more than 30% new content words -- which is precisely what a business rewrite
 * is -- so business silently fell back to the mode-blind [RuleBasedCleaner].
 *
 * These lock in that a genuine business rewrite survives while the guards that actually
 * matter (never answering the dictation, never inventing facts) still hold in every mode.
 */
class CleanupModeDifferentiationTest {

    private val dictation = "um so i was gonna call you yesterday but i totally forgot sorry about that"

    /** The rewrite that motivated this work: rejected at 50% new content words under the old flat rule. */
    private val businessRewrite = "I intended to call you yesterday, but it slipped my mind. My apologies."

    @Test
    fun `business accepts a genuine professional rewrite`() {
        assertTrue(
            "A business rewrite is the whole point of the mode; it must not be rejected",
            TextCleanupUtils.isFaithful(dictation, businessRewrite, CleanupMode.BUSINESS)
        )
    }

    @Test
    fun `informal rejects the same rewrite as too heavy a hand`() {
        assertFalse(
            "Informal is supposed to preserve the speaker's own words",
            TextCleanupUtils.isFaithful(dictation, businessRewrite, CleanupMode.INFORMAL)
        )
    }

    @Test
    fun `the worked example from the diagnosis now survives business`() {
        val source = "um so I was thinking like maybe we could uh push the deadline back a week you know"
        val edited = "I propose we extend the deadline by one week."

        assertFalse(TextCleanupUtils.isFaithful(source, edited, CleanupMode.INFORMAL))
        assertTrue(TextCleanupUtils.isFaithful(source, edited, CleanupMode.BUSINESS))
    }

    @Test
    fun `every mode still refuses to answer a dictated question`() {
        CleanupMode.entries.forEach { mode ->
            assertFalse(
                "$mode must never turn a dictated question into a reply",
                TextCleanupUtils.isFaithful("Hi, how are you?", "I'm fine, how about you?", mode)
            )
        }
    }

    @Test
    fun `every mode still preserves a dictated question as a question`() {
        CleanupMode.entries.forEach { mode ->
            assertFalse(
                "$mode dropped a question mark",
                TextCleanupUtils.isFaithful(
                    "Are you coming to the meeting?",
                    "You are coming to the meeting.",
                    mode
                )
            )
        }
    }

    @Test
    fun `no mode may invent a number that was not dictated`() {
        CleanupMode.entries.forEach { mode ->
            assertFalse(
                "$mode allowed a fabricated figure through",
                TextCleanupUtils.isFaithful(
                    "revenue was up a lot this quarter",
                    "Revenue increased by 42% this quarter.",
                    mode
                )
            )
        }
    }

    @Test
    fun `numbers that were dictated survive being reformatted`() {
        assertTrue(
            TextCleanupUtils.isFaithful(
                "we shipped 3 builds last week",
                "We shipped 3 builds last week.",
                CleanupMode.BUSINESS
            )
        )
    }

    @Test
    fun `no mode may balloon the transcript`() {
        val source = "the sky is blue"
        val padded = "the sky is blue because of Rayleigh scattering of sunlight in the atmosphere"
        CleanupMode.entries.forEach { mode ->
            assertFalse("$mode allowed an explanatory expansion", TextCleanupUtils.isFaithful(source, padded, mode))
        }
    }

    /**
     * Regression: the length cap was `max(len * ratio, len + 80)` with a flat slack, so at a
     * typical 74-character dictation both business and standard capped at 154 characters and
     * business's stricter ratio never applied. "Concise" was asserted in a comment only.
     */
    @Test
    fun `business rejects padding that standard tolerates at ordinary dictation length`() {
        val source = "we should probably get the thing sorted out before the end of the week"
        // Padded with the source's own words so the vocabulary threshold cannot fire and the
        // length rule is the only thing under test.
        val padded = "$source $source"

        assertTrue(
            "standard's looser policy should still accept this",
            TextCleanupUtils.isFaithful(source, padded, CleanupMode.STANDARD)
        )
        assertFalse(
            "business must not let the text grow",
            TextCleanupUtils.isFaithful(source, padded, CleanupMode.BUSINESS)
        )
    }

    @Test
    fun `business tolerates punctuation and capitalisation on a short dictation`() {
        assertTrue(
            "the length slack has to leave room for terminal punctuation",
            TextCleanupUtils.isFaithful("send the report today", "Send the report today.", CleanupMode.BUSINESS)
        )
    }

    @Test
    fun `business is the most permissive and informal the least`() {
        val ratios = CleanupMode.entries.associateWith { it.policy.maxIntroducedContentWordRatio }

        assertTrue(ratios.getValue(CleanupMode.BUSINESS) > ratios.getValue(CleanupMode.STANDARD))
        assertTrue(ratios.getValue(CleanupMode.STANDARD) > ratios.getValue(CleanupMode.INFORMAL))
    }

    @Test
    fun `each mode sends the model a distinct instruction and example`() {
        val instructions = CleanupMode.entries.map { it.instruction }
        val examples = CleanupMode.entries.map { it.exampleOutput }

        assertEquals("instructions must not be shared between modes", instructions.size, instructions.toSet().size)
        assertEquals("examples must not be shared between modes", examples.size, examples.toSet().size)
    }

    @Test
    fun `the prompt actually differs between informal and business`() {
        var informalPrompt = ""
        var businessPrompt = ""
        QwenCleaner { prompt, _ -> informalPrompt = prompt; "ok" }
            .clean(dictation, CleanupMode.INFORMAL, emptyList())
        QwenCleaner { prompt, _ -> businessPrompt = prompt; "ok" }
            .clean(dictation, CleanupMode.BUSINESS, emptyList())

        assertNotEquals(informalPrompt, businessPrompt)
        assertTrue(businessPrompt.contains(CleanupMode.BUSINESS.exampleOutput))
        assertFalse(businessPrompt.contains(CleanupMode.INFORMAL.exampleOutput))
    }
}
