package dev.chaseallbright.localscribe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two modes, one contract: keep the speaker's words. They differ in who does the work --
 * Informal is deterministic, Standard sends the text to the model -- so both must reject a
 * rewrite and both must hold the invariants that stop a model answering the dictation.
 */
class CleanupModeDifferentiationTest {

    private val dictation = "um so I'm gonna call you tomorrow, but I haven't haven't checked the schedule yet"

    /** A rewrite rather than an edit: it swaps most of the speaker's words for synonyms. */
    private val rewrite = "I intended to telephone you tomorrow, though I have not reviewed my agenda."

    @Test
    fun `standard rejects a rewrite as too heavy a hand`() {
        assertFalse(
            "Standard keeps the speaker's exact words, so a synonym swap is a rewrite",
            TextCleanupUtils.isFaithful(dictation, rewrite, CleanupMode.STANDARD)
        )
    }

    @Test
    fun `informal rejects the same rewrite`() {
        assertFalse(
            "Informal is the least permissive mode and must not accept a paraphrase",
            TextCleanupUtils.isFaithful(dictation, rewrite, CleanupMode.INFORMAL)
        )
    }

    @Test
    fun `informal output is the speaker's own words in a texting register`() {
        assertEquals(
            "hey man what's going on",
            RuleBasedCleaner().clean("Hey man, what's going on?", CleanupMode.INFORMAL, emptyList()).text
        )
    }

    @Test
    fun `standard collapses a repeated word while keeping the rest`() {
        assertEquals(
            "So I'm gonna call you tomorrow, but I haven't checked the schedule yet.",
            RuleBasedCleaner().clean(dictation, CleanupMode.STANDARD, emptyList()).text
        )
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
                "$mode accepted a fabricated figure",
                TextCleanupUtils.isFaithful(
                    "123 people attended the meeting",
                    "one hundred twenty three people attended the meeting",
                    mode
                )
            )
        }
    }

    @Test
    fun `numbers that were dictated are kept`() {
        assertTrue(
            TextCleanupUtils.isFaithful(
                "we shipped 12 builds this quarter",
                "We shipped 12 builds this quarter.",
                CleanupMode.STANDARD
            )
        )
    }

    @Test
    fun `no mode may balloon the transcript`() {
        val source = "hello there"
        val padded = "this is a very long sentence that says the same thing but with many more words added in"
        CleanupMode.entries.forEach { mode ->
            assertFalse("$mode allowed an explanatory expansion", TextCleanupUtils.isFaithful(source, padded, mode))
        }
    }

    @Test
    fun `standard tolerates punctuation and capitalisation on a short dictation`() {
        assertTrue(
            "the length slack exists so terminal punctuation is not punished",
            TextCleanupUtils.isFaithful("send the report today", "Send the report today.", CleanupMode.STANDARD)
        )
    }

    @Test
    fun `standard allows more vocabulary change than informal`() {
        assertTrue(
            CleanupMode.STANDARD.policy.maxIntroducedContentWordRatio >
                CleanupMode.INFORMAL.policy.maxIntroducedContentWordRatio
        )
    }

    @Test
    fun `standard accepts the grammar fix it is meant to produce`() {
        val cleaned = "So I'm gonna call you tomorrow, but I haven't checked the schedule yet."
        assertTrue(TextCleanupUtils.isFaithful(dictation, cleaned, CleanupMode.STANDARD))
    }

    @Test
    fun `informal never reaches the model at all`() {
        var generatorCalled = false
        val cleaner = QwenCleaner { _: String, _: Int -> generatorCalled = true; "anything" }
        val result = cleaner.clean("Hey man, what's going on?", CleanupMode.INFORMAL, emptyList())

        assertFalse("informal is deterministic by design", generatorCalled)
        assertEquals(CleanupBackend.VERBATIM, result.backend)
    }

    @Test
    fun `only standard reaches the model`() {
        assertEquals(listOf(CleanupMode.STANDARD), CleanupMode.entries.filter { it.usesLlm })
        assertTrue("the LLM mode needs a brief", CleanupMode.STANDARD.instruction.isNotBlank())
        assertTrue("informal has no brief because it never reaches a model", CleanupMode.INFORMAL.instruction.isBlank())
    }

    @Test
    fun `the standard prompt carries its brief and example`() {
        var prompt = ""
        QwenCleaner { text: String, _: Int -> prompt = text; "ok" }
            .clean(dictation, CleanupMode.STANDARD, emptyList())

        assertTrue(prompt.contains(CleanupMode.STANDARD.instruction))
        assertTrue(prompt.contains(CleanupMode.STANDARD.exampleOutput))
    }
}
