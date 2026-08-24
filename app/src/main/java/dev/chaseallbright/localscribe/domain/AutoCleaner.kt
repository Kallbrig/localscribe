package dev.chaseallbright.localscribe.domain

import dev.chaseallbright.localscribe.bridge.LlamaBridge
import java.io.File

enum class CleanerBackend { RULES, LLAMA_CPP }

/**
 * Loads the configured Qwen2.5 GGUF model if present and valid, otherwise falls back to
 * [RuleBasedCleaner] -- cleanup must never block dictation on a model that failed to load.
 */
class AutoCleaner(modelPath: String?, contextSize: Int = 2048, threads: Int = 4) : Cleaner {
    val backend: CleanerBackend
    private val delegate: Cleaner

    init {
        val bridge = modelPath
            ?.let { path -> if (File(path).isFile) path else null }
            ?.let { path -> runCatching { LlamaBridge.load(path, contextSize, threads) }.getOrNull() }

        if (bridge != null) {
            delegate = QwenCleaner(bridge)
            backend = CleanerBackend.LLAMA_CPP
        } else {
            delegate = RuleBasedCleaner()
            backend = CleanerBackend.RULES
        }
    }

    override fun clean(text: String, mode: CleanupMode, vocabulary: List<String>): String =
        delegate.clean(text, mode, vocabulary)
}
