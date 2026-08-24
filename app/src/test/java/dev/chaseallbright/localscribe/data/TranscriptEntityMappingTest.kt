package dev.chaseallbright.localscribe.data

import dev.chaseallbright.localscribe.domain.CleanupBackend
import dev.chaseallbright.localscribe.domain.CleanupMode
import dev.chaseallbright.localscribe.domain.Transcript
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptEntityMappingTest {

    private val transcript = Transcript(
        raw = "raw",
        cleaned = "cleaned",
        language = "en",
        durationSeconds = 1.5f,
        mode = CleanupMode.STANDARD,
        backend = CleanupBackend.RULES_FALLBACK
    )

    @Test
    fun `backend round-trips through the entity`() {
        val roundTripped = transcript.toEntity(createdAtEpochMillis = 123L).toDomain()
        assertEquals(CleanupBackend.RULES_FALLBACK, roundTripped.backend)
    }

    @Test
    fun `unrecognized stored backend maps to UNKNOWN`() {
        val entity = transcript.toEntity(createdAtEpochMillis = 123L).copy(cleanupBackend = "garbage")
        assertEquals(CleanupBackend.UNKNOWN, entity.toDomain().backend)
    }

    @Test
    fun `legacy default value maps to UNKNOWN`() {
        val entity = transcript.toEntity(createdAtEpochMillis = 123L).copy(cleanupBackend = "UNKNOWN")
        assertEquals(CleanupBackend.UNKNOWN, entity.toDomain().backend)
    }
}
