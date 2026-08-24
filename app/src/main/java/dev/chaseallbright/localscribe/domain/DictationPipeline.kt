package dev.chaseallbright.localscribe.domain

private const val SAMPLE_RATE_HZ = 16_000

class DictationPipeline(
    private val transcriber: Transcriber,
    private val cleaner: Cleaner
) {
    fun process(samples: FloatArray, mode: CleanupMode, vocabulary: List<String>): Transcript {
        val result = transcriber.transcribe(samples, vocabulary)
        val cleaned = cleaner.clean(result.text, mode, vocabulary)
        val durationSeconds = samples.size.toFloat() / SAMPLE_RATE_HZ
        return Transcript(result.text, cleaned.text, result.language, durationSeconds, mode, cleaned.backend)
    }
}
