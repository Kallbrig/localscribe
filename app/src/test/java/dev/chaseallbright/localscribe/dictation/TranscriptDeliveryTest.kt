package dev.chaseallbright.localscribe.dictation

import dev.chaseallbright.localscribe.domain.CleanupBackend
import dev.chaseallbright.localscribe.domain.CleanupMode
import dev.chaseallbright.localscribe.domain.Transcript
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

private fun transcript(cleaned: String = "cleaned text") = Transcript(
    raw = "raw text",
    cleaned = cleaned,
    language = "en",
    durationSeconds = 1.0f,
    mode = CleanupMode.STANDARD,
    backend = CleanupBackend.RULES
)

class TranscriptDeliveryTest {

    @Test
    fun `saves before publishing and publishes the same transcript once`() = runTest {
        val transcript = transcript()
        val events = mutableListOf<String>()
        var published: Transcript? = null

        deliverTranscript(
            transcript = transcript,
            save = { events += "save" },
            published = {
                events += "publish"
                published = it
            }
        )

        assertEquals(listOf("save", "publish"), events)
        assertSame(transcript, published)
    }

    @Test
    fun `a failed save still publishes the transcript once and reports the failure`() = runTest {
        val transcript = transcript()
        val diskFull = IllegalStateException("database or disk is full")
        var publishes = 0
        var reported: Exception? = null
        var failures = 0

        deliverTranscript(
            transcript = transcript,
            save = { throw diskFull },
            published = { publishes++ },
            onSaveFailure = {
                failures++
                reported = it
            }
        )

        assertEquals(1, publishes)
        assertEquals(1, failures)
        assertSame(diskFull, reported)
    }

    @Test
    fun `cancellation during save propagates without publishing or reporting`() = runTest {
        var publishes = 0
        var failures = 0
        var caught: CancellationException? = null

        try {
            deliverTranscript(
                transcript = transcript(),
                save = { throw CancellationException("service destroyed") },
                published = { publishes++ },
                onSaveFailure = { failures++ }
            )
        } catch (e: CancellationException) {
            caught = e
        }

        assertNotNull(caught)
        assertEquals(0, publishes)
        assertEquals(0, failures)
    }

    @Test
    fun `a delivery failure propagates and is not reported as a save failure`() = runTest {
        val noField = IllegalStateException("no focused field")
        var failures = 0
        var caught: Exception? = null

        try {
            deliverTranscript(
                transcript = transcript(),
                save = { },
                published = { throw noField },
                onSaveFailure = { failures++ }
            )
        } catch (e: Exception) {
            caught = e
        }

        assertSame(noField, caught)
        assertEquals(0, failures)
        assertFalse(caught is CancellationException)
    }
}
