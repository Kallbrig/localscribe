package dev.chaseallbright.localscribe.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.chaseallbright.localscribe.domain.CleanupBackend
import dev.chaseallbright.localscribe.domain.CleanupMode
import dev.chaseallbright.localscribe.domain.Transcript

@Entity(tableName = "transcripts")
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAtEpochMillis: Long,
    val raw: String,
    val cleaned: String,
    val language: String,
    val durationSeconds: Float,
    val mode: String,
    @ColumnInfo(defaultValue = "UNKNOWN")
    val cleanupBackend: String = CleanupBackend.UNKNOWN.name,
    /** Zero on rows written before timings were kept; the UI omits it rather than showing 0ms. */
    @ColumnInfo(defaultValue = "0")
    val transcribeMillis: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val cleanupMillis: Long = 0
)

fun Transcript.toEntity(createdAtEpochMillis: Long = System.currentTimeMillis()): TranscriptEntity =
    TranscriptEntity(
        createdAtEpochMillis = createdAtEpochMillis,
        raw = raw,
        cleaned = cleaned,
        language = language,
        durationSeconds = durationSeconds,
        mode = mode.name,
        cleanupBackend = backend.name,
        transcribeMillis = transcribeMillis,
        cleanupMillis = cleanupMillis
    )

fun TranscriptEntity.toDomain(): Transcript =
    Transcript(
        raw = raw,
        cleaned = cleaned,
        language = language,
        durationSeconds = durationSeconds,
        mode = CleanupMode.valueOf(mode),
        backend = runCatching { CleanupBackend.valueOf(cleanupBackend) }
            .getOrDefault(CleanupBackend.UNKNOWN),
        transcribeMillis = transcribeMillis,
        cleanupMillis = cleanupMillis
    )
