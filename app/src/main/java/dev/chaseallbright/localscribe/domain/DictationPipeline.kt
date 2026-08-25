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
        onStageTiming("transcribe", (System.nanoTime() - transcribeStart) / 1_000_000)

        val cleanStart = System.nanoTime()
        val cleaned = cleaner.clean(result.text, mode, vocabulary)
        onStageTiming("cleanup", (System.nanoTime() - cleanStart) / 1_000_000)

        val durationSeconds = samples.size.toFloat() / SAMPLE_RATE_HZ
        return Transcript(result.text, cleaned.text, result.language, durationSeconds, mode, cleaned.backend)
    }
}
