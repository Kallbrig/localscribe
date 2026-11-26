package dev.chaseallbright.localscribe.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleBasedCleanerTest {
    private val cleaner = RuleBasedCleaner()

    @Test
    fun `removes filler words in standard mode`() {
        val result = cleaner.clean("um so I I want want to go", CleanupMode.STANDARD, emptyList()).text
        assertEquals("So I want to go.", result)
    }

    @Test
    fun `informal drops disfluencies but keeps slang and casing`() {
        // Informal routes to VerbatimFormatter: "um" is a sound, "like" is the register.
        // See VerbatimFormatterTest for the full contract.
        assertEquals("I guess", cleaner.clean("um I guess", CleanupMode.INFORMAL, emptyList()).text)
        assertEquals(
            "like I guess so",
            cleaner.clean("Like, I guess so.", CleanupMode.INFORMAL, emptyList()).text
        )
    }

    @Test
    fun `collapses immediately repeated words`() {
        val result = cleaner.clean("I I want want to go", CleanupMode.STANDARD, emptyList()).text
        assertEquals("I want to go.", result)
    }

    @Test
    fun `capitalizes first letter and adds a terminal period in standard mode`() {
        val result = cleaner.clean("this is a test", CleanupMode.STANDARD, emptyList()).text
        assertEquals("This is a test.", result)
    }

    @Test
    fun `keeps an existing question mark instead of adding a period`() {
        val result = cleaner.clean("is this a test?", CleanupMode.STANDARD, emptyList()).text
        assertEquals("Is this a test?", result)
    }

    @Test
    fun `restores vocabulary casing regardless of how whisper transcribed it`() {
        val result = cleaner.clean("call kallbrig about the thing", CleanupMode.STANDARD, listOf("Kallbrig")).text
        assertEquals("Call Kallbrig about the thing.", result)
    }

    @Test
    fun `collapses extra whitespace`() {
        val result = cleaner.clean("hello   hello friend", CleanupMode.STANDARD, emptyList()).text
        assertEquals("Hello friend.", result)
    }

    @Test
    fun `reports RULES backend`() {
        val result = cleaner.clean("hello there", CleanupMode.STANDARD, emptyList())
        assertEquals(CleanupBackend.RULES, result.backend)
    }
}
