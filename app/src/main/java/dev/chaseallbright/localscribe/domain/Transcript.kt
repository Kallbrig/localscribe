package dev.chaseallbright.localscribe.domain

data class Transcript(
    val raw: String,
    val cleaned: String,
    val language: String,
    val durationSeconds: Float,
    val mode: CleanupMode,
    val backend: CleanupBackend
)

data class TranscriptionResult(
    val text: String,
    val language: String
)
