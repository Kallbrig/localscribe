package dev.chaseallbright.localscribe.transfer

import dev.chaseallbright.localscribe.data.TranscriptEntity
import dev.chaseallbright.localscribe.domain.CleanupBackend
import dev.chaseallbright.localscribe.domain.CleanupMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptArchiveTest {

    private fun entity(
        createdAt: Long = 1_756_000_000_000,
        raw: String = "hey man whats going on",
        cleaned: String = "hey man what's going on",
        mode: CleanupMode = CleanupMode.INFORMAL,
        backend: CleanupBackend = CleanupBackend.VERBATIM
    ) = TranscriptEntity(
        createdAtEpochMillis = createdAt,
        raw = raw,
        cleaned = cleaned,
        language = "en",
        durationSeconds = 2.4f,
        mode = mode.name,
        cleanupBackend = backend.name
    )

    @Test
    fun `round trips a transcript exactly`() {
        val original = entity()

        val decoded = TranscriptArchive.decode(
            TranscriptArchive.encode(emptyList(), listOf(original), "0.1.5", 1_756_000_000_000)
        )

        assertEquals(1, decoded.transcripts.size)
        val restored = decoded.transcripts.single()
        assertEquals(original.createdAtEpochMillis, restored.createdAtEpochMillis)
        assertEquals(original.raw, restored.raw)
        assertEquals(original.cleaned, restored.cleaned)
        assertEquals(original.language, restored.language)
        assertEquals(original.durationSeconds, restored.durationSeconds, 0.001f)
        assertEquals(original.mode, restored.mode)
        assertEquals(original.cleanupBackend, restored.cleanupBackend)
    }

    @Test
    fun `text that would break CSV survives intact`() {
        // The reason this format is JSON. Every one of these is a CSV escaping hazard.
        val nasty = "he said \"hello, there\"\nthen left; 100% sure\ttab\r\nwindows newline"
        val decoded = TranscriptArchive.decode(
            TranscriptArchive.encode(emptyList(), listOf(entity(cleaned = nasty, raw = nasty)), "0.1.5", 1L)
        )

        assertEquals(nasty, decoded.transcripts.single().cleaned)
        assertEquals(nasty, decoded.transcripts.single().raw)
    }

    @Test
    fun `unicode and emoji survive`() {
        val text = "café über 日本語 🎤 naïve"
        val decoded = TranscriptArchive.decode(
            TranscriptArchive.encode(listOf(text), listOf(entity(cleaned = text)), "0.1.5", 1L)
        )

        assertEquals(text, decoded.transcripts.single().cleaned)
        assertEquals(listOf(text), decoded.vocabulary)
    }

    @Test
    fun `round trips vocabulary`() {
        val words = listOf("Kallbrig", "LocalScribe", "Dana")
        val decoded = TranscriptArchive.decode(
            TranscriptArchive.encode(words, emptyList(), "0.1.5", 1L)
        )

        assertEquals(words, decoded.vocabulary)
    }

    @Test
    fun `an empty export round trips`() {
        val decoded = TranscriptArchive.decode(
            TranscriptArchive.encode(emptyList(), emptyList(), "0.1.5", 1L)
        )

        assertEquals(emptyList<String>(), decoded.vocabulary)
        assertEquals(emptyList<TranscriptEntity>(), decoded.transcripts)
    }

    @Test
    fun `a file that is not an export is refused by name`() {
        val error = assertThrows(TranscriptArchive.UnsupportedArchive::class.java) {
            TranscriptArchive.decode("""{"some":"other json"}""")
        }
        assertTrue(error.message!!.contains("not a LocalScribe export"))
    }

    @Test
    fun `garbage is refused rather than crashing`() {
        assertThrows(TranscriptArchive.UnsupportedArchive::class.java) {
            TranscriptArchive.decode("this is not json at all")
        }
    }

    @Test
    fun `a newer format version is refused with a useful message`() {
        val future = """{"format":"localscribe-export","version":99,"transcripts":[]}"""

        val error = assertThrows(TranscriptArchive.UnsupportedArchive::class.java) {
            TranscriptArchive.decode(future)
        }
        assertTrue(error.message!!.contains("newer version"))
    }

    @Test
    fun `an unknown mode is kept rather than dropping the transcript`() {
        val json = """
            {"format":"localscribe-export","version":1,"transcripts":[
              {"createdAt":123,"raw":"a","cleaned":"a","language":"en",
               "durationSeconds":1.0,"mode":"SOME_FUTURE_MODE","cleanupBackend":"NEW_ENGINE"}
            ]}
        """.trimIndent()

        val decoded = TranscriptArchive.decode(json)

        assertEquals("the text is the valuable part; an enum growing must not lose it", 1, decoded.transcripts.size)
        assertEquals("SOME_FUTURE_MODE", decoded.transcripts.single().mode)
    }

    @Test
    fun `rows missing their text are skipped and counted`() {
        val json = """
            {"format":"localscribe-export","version":1,"transcripts":[
              {"createdAt":123,"cleaned":"kept"},
              {"createdAt":123,"cleaned":""},
              {"cleaned":"no timestamp"}
            ]}
        """.trimIndent()

        val decoded = TranscriptArchive.decode(json)

        assertEquals(1, decoded.transcripts.size)
        assertEquals(2, decoded.skipped)
    }

    @Test
    fun `re-importing the same file inserts nothing`() {
        val existing = listOf(entity(createdAt = 1), entity(createdAt = 2, cleaned = "second"))

        assertEquals(emptyList<TranscriptEntity>(), TranscriptArchive.transcriptsToInsert(existing, existing))
    }

    @Test
    fun `only genuinely new transcripts are inserted`() {
        val existing = listOf(entity(createdAt = 1))
        val incoming = listOf(entity(createdAt = 1), entity(createdAt = 2, cleaned = "new one"))

        val toInsert = TranscriptArchive.transcriptsToInsert(incoming, existing)

        assertEquals(1, toInsert.size)
        assertEquals("new one", toInsert.single().cleaned)
    }

    @Test
    fun `two dictations at the same instant with different text both survive`() {
        val existing = listOf(entity(createdAt = 5, cleaned = "one"))
        val incoming = listOf(entity(createdAt = 5, cleaned = "two"))

        assertEquals(1, TranscriptArchive.transcriptsToInsert(incoming, existing).size)
    }

    @Test
    fun `duplicates inside one file are collapsed`() {
        val incoming = listOf(entity(createdAt = 9), entity(createdAt = 9))

        assertEquals(1, TranscriptArchive.transcriptsToInsert(incoming, emptyList()).size)
    }

    @Test
    fun `vocabulary merges case-insensitively without duplicating`() {
        val toInsert = TranscriptArchive.vocabularyToInsert(
            incoming = listOf("Kallbrig", "kallbrig", "  Dana  ", "", "New"),
            existing = listOf("KALLBRIG")
        )

        assertEquals(listOf("Dana", "New"), toInsert)
    }
}
