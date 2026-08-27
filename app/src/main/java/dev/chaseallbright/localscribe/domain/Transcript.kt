package dev.chaseallbright.localscribe.domain

data class Transcript(
    val raw: String,
    val cleaned: String,
    val language: String,
    /** How long the speaker talked for -- audio length, not processing time. */
    val durationSeconds: Float,
    val mode: CleanupMode,
    val backend: CleanupBackend,
    /** Wall time spent in speech-to-text. Zero for rows recorded before this was kept. */
    val transcribeMillis: Long = 0,
    /** Wall time spent in cleanup. Zero for informal, which does no model work. */
    val cleanupMillis: Long = 0
)

data class TranscriptionResult(
    val text: String,
    val language: String
)
