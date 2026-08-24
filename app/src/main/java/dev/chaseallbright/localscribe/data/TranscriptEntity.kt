package dev.chaseallbright.localscribe.data

import androidx.room.Entity
import androidx.room.PrimaryKey
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
    val mode: String
)

fun Transcript.toEntity(createdAtEpochMillis: Long = System.currentTimeMillis()): TranscriptEntity =
    TranscriptEntity(
        createdAtEpochMillis = createdAtEpochMillis,
        raw = raw,
        cleaned = cleaned,
        language = language,
        durationSeconds = durationSeconds,
        mode = mode.name
    )

fun TranscriptEntity.toDomain(): Transcript =
    Transcript(
        raw = raw,
        cleaned = cleaned,
        language = language,
        durationSeconds = durationSeconds,
        mode = CleanupMode.valueOf(mode)
    )
