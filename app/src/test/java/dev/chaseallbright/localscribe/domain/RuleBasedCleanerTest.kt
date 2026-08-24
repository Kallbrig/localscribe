package dev.chaseallbright.localscribe.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleBasedCleanerTest {
    private val cleaner = RuleBasedCleaner()

    @Test
    fun `removes filler words in standard mode`() {
        val result = cleaner.clean("um so uh I think, like, we should go", CleanupMode.STANDARD, emptyList())
        assertEquals("So I think, we should go.", result)
    }

    @Test
    fun `keeps filler words in informal mode`() {
        val result = cleaner.clean("um I guess", CleanupMode.INFORMAL, emptyList())
        assertEquals("Um I guess", result)
    }

    @Test
    fun `collapses immediately repeated words`() {
        val result = cleaner.clean("I I want want to go", CleanupMode.STANDARD, emptyList())
        assertEquals("I want to go.", result)
    }

    @Test
    fun `capitalizes first letter and adds terminal period in business mode`() {
        val result = cleaner.clean("this is a test", CleanupMode.BUSINESS, emptyList())
        assertEquals("This is a test.", result)
    }

    @Test
    fun `does not force terminal punctuation in casual mode`() {
        val result = cleaner.clean("this is a test", CleanupMode.CASUAL, emptyList())
        assertEquals("This is a test", result)
    }

    @Test
    fun `restores vocabulary casing regardless of how whisper transcribed it`() {
        val result = cleaner.clean("call kallbrig about the project", CleanupMode.STANDARD, listOf("Kallbrig"))
        assertEquals("Call Kallbrig about the project.", result)
    }

    @Test
    fun `collapses extra whitespace`() {
        val result = cleaner.clean("hello   there    friend", CleanupMode.CASUAL, emptyList())
        assertEquals("Hello there friend", result)
    }
}
