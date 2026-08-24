package dev.chaseallbright.localscribe.domain

import dev.chaseallbright.localscribe.bridge.WhisperBridge

class WhisperTranscriber(private val whisper: WhisperBridge, private val language: String = "en") : Transcriber {
    override fun transcribe(samples: FloatArray, vocabulary: List<String>): TranscriptionResult {
        val prompt = if (vocabulary.isEmpty()) "" else "Important vocabulary: " + vocabulary.joinToString(", ")
        val text = whisper.transcribe(samples, language = language, initialPrompt = prompt)
        return TranscriptionResult(text.trim(), language)
    }
}
