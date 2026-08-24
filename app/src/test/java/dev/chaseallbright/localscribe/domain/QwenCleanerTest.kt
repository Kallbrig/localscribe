package dev.chaseallbright.localscribe.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class QwenCleanerTest {

    @Test
    fun `faithful generator output is used with QWEN backend`() {
        val cleaner = QwenCleaner { _, _ -> "Hello world, how are you?" }

        val result = cleaner.clean("hello world how are you", CleanupMode.STANDARD, emptyList())

        assertEquals(CleanupBackend.QWEN, result.backend)
        assertEquals("Hello world, how are you?", result.text)
    }

    @Test
    fun `empty generator output falls back to rules`() {
        val cleaner = QwenCleaner { _, _ -> "" }
        val input = "um so uh I think, like, we should go"

        val result = cleaner.clean(input, CleanupMode.STANDARD, emptyList())

        assertEquals(CleanupBackend.RULES_FALLBACK, result.backend)
        assertEquals(RuleBasedCleaner().clean(input, CleanupMode.STANDARD, emptyList()).text, result.text)
    }

    @Test
    fun `wildly unfaithful generator output falls back to rules`() {
        val cleaner = QwenCleaner { _, _ -> "I'm fine, thanks for asking!" }

        val result = cleaner.clean("Hi, how are you?", CleanupMode.STANDARD, emptyList())

        assertEquals(CleanupBackend.RULES_FALLBACK, result.backend)
    }
}
