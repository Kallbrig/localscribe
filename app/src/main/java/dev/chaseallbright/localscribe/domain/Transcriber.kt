package dev.chaseallbright.localscribe.domain

/** [samples] is 16kHz mono PCM in [-1, 1]. */
fun interface Transcriber {
    fun transcribe(samples: FloatArray, vocabulary: List<String>): TranscriptionResult
}
