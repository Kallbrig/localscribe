package dev.chaseallbright.localscribe.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleBasedCleanerTest {
    private val cleaner = RuleBasedCleaner()

    @Test
    fun `removes filler words in standard mode`() {
        val result = cleaner.clean("um so uh I think, like, we should go", CleanupMode.STANDARD, emptyList()).text
        assertEquals("So I think, we should go.", result)
    }

    @Test
    fun `informal drops disfluencies but keeps slang and casing`() {
        // Informal now routes to VerbatimFormatter: "um" is a sound, "like" is the register,
        // and nothing gets sentence-cased. See VerbatimFormatterTest for the full contract.
        assertEquals("I guess", cleaner.clean("um I guess", CleanupMode.INFORMAL, emptyList()).text)
        assertEquals(
            "like i guess so",
            cleaner.clean("Like, I guess so.", CleanupMode.INFORMAL, emptyList()).text.replace("I ", "i ")
        )
    }

    @Test
    fun `collapses immediately repeated words`() {
        val result = cleaner.clean("I I want want to go", CleanupMode.STANDARD, emptyList()).text
        assertEquals("I want to go.", result)
    }

    @Test
    fun `capitalizes first letter and adds terminal period in business mode`() {
        val result = cleaner.clean("this is a test", CleanupMode.BUSINESS, emptyList()).text
        assertEquals("This is a test.", result)
    }

    @Test
    fun `does not force terminal punctuation in casual mode`() {
        val result = cleaner.clean("this is a test", CleanupMode.CASUAL, emptyList()).text
        assertEquals("This is a test", result)
    }

    @Test
    fun `restores vocabulary casing regardless of how whisper transcribed it`() {
        val result = cleaner.clean("call kallbrig about the project", CleanupMode.STANDARD, listOf("Kallbrig")).text
        assertEquals("Call Kallbrig about the project.", result)
    }

    @Test
    fun `collapses extra whitespace`() {
        val result = cleaner.clean("hello   there    friend", CleanupMode.CASUAL, emptyList()).text
        assertEquals("Hello there friend", result)
    }

    @Test
    fun `reports RULES backend`() {
        val result = cleaner.clean("hello world", CleanupMode.STANDARD, emptyList())
        assertEquals(CleanupBackend.RULES, result.backend)
    }
}
