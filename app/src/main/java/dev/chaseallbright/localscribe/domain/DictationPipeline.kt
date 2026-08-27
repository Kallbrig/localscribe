package dev.chaseallbright.localscribe.domain

private const val SAMPLE_RATE_HZ = 16_000

class DictationPipeline(
    private val transcriber: Transcriber,
    private val cleaner: Cleaner,
    /** Reports how long each stage took, so callers can log where dictation latency goes. */
    private val onStageTiming: (stage: String, millis: Long) -> Unit = { _, _ -> }
) {
    fun process(samples: FloatArray, mode: CleanupMode, vocabulary: List<String>): Transcript {
        val transcribeStart = System.nanoTime()
        val result = transcriber.transcribe(samples, vocabulary)
        val transcribeMillis = (System.nanoTime() - transcribeStart) / 1_000_000
        onStageTiming("transcribe", transcribeMillis)

        val cleanStart = System.nanoTime()
        val cleaned = cleaner.clean(result.text, mode, vocabulary)
        val cleanupMillis = (System.nanoTime() - cleanStart) / 1_000_000
        onStageTiming("cleanup", cleanupMillis)

        // Audio length, not elapsed processing time -- how long the speaker talked.
        val durationSeconds = samples.size.toFloat() / SAMPLE_RATE_HZ
        return Transcript(
            raw = result.text,
            cleaned = cleaned.text,
            language = result.language,
            durationSeconds = durationSeconds,
            mode = mode,
            backend = cleaned.backend,
            transcribeMillis = transcribeMillis,
            cleanupMillis = cleanupMillis
        )
    }
}
