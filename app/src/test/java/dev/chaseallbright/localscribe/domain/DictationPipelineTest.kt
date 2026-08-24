package dev.chaseallbright.localscribe.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationPipelineTest {

    @Test
    fun `process wires transcriber output into cleaner and returns a full transcript`() {
        val samples = FloatArray(16_000) // 1 second at 16kHz
        var cleanerReceivedText: String? = null
        var cleanerReceivedMode: CleanupMode? = null
        var cleanerReceivedVocab: List<String>? = null

        val transcriber = Transcriber { _, _ -> TranscriptionResult("raw text", "en") }
        val cleaner = Cleaner { text, mode, vocabulary ->
            cleanerReceivedText = text
            cleanerReceivedMode = mode
            cleanerReceivedVocab = vocabulary
            "cleaned text"
        }

        val pipeline = DictationPipeline(transcriber, cleaner)
        val result = pipeline.process(samples, CleanupMode.STANDARD, listOf("Kallbrig"))

        assertEquals("raw text", result.raw)
        assertEquals("cleaned text", result.cleaned)
        assertEquals("en", result.language)
        assertEquals(CleanupMode.STANDARD, result.mode)
        assertEquals(1.0f, result.durationSeconds, 0.001f)

        assertEquals("raw text", cleanerReceivedText)
        assertEquals(CleanupMode.STANDARD, cleanerReceivedMode)
        assertEquals(listOf("Kallbrig"), cleanerReceivedVocab)
    }

    @Test
    fun `duration is derived from sample count at 16kHz regardless of transcriber output`() {
        val samples = FloatArray(32_000) // 2 seconds
        val pipeline = DictationPipeline(
            transcriber = Transcriber { _, _ -> TranscriptionResult("x", "en") },
            cleaner = Cleaner { text, _, _ -> text }
        )

        val result = pipeline.process(samples, CleanupMode.CASUAL, emptyList())

        assertEquals(2.0f, result.durationSeconds, 0.001f)
    }
}
