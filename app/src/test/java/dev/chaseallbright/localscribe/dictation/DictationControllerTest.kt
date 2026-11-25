package dev.chaseallbright.localscribe.dictation

import dev.chaseallbright.localscribe.domain.CleanupBackend
import dev.chaseallbright.localscribe.domain.CleanupMode
import dev.chaseallbright.localscribe.domain.Transcript
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun sampleTranscript() = Transcript(
    raw = "raw text",
    cleaned = "cleaned text",
    language = "en",
    durationSeconds = 1.0f,
    mode = CleanupMode.STANDARD,
    backend = CleanupBackend.RULES
)

@OptIn(ExperimentalCoroutinesApi::class)
class DictationControllerTest {

    @Test
    fun `publishing with no collector reports not delivered`() = runTest {
        // transcriptReady has no replay, so this emit goes nowhere. Reporting false is what
        // lets the dictation service fall back to the clipboard instead of losing the text.
        assertFalse(DictationController.publishTranscript(sampleTranscript()))
    }

    @Test
    fun `publishing with a collector reports delivered`() = runTest {
        val received = mutableListOf<Transcript>()
        val job = backgroundScope.launch {
            DictationController.transcriptReady.collect { received += it }
        }
        runCurrent()

        assertTrue(DictationController.publishTranscript(sampleTranscript()))
        // emit buffers for the collector rather than running it inline, so let the test
        // dispatcher resume it before asserting what it actually received.
        runCurrent()
        assertEquals(1, received.size)
        assertEquals("cleaned text", received.single().cleaned)

        job.cancel()
    }
}
