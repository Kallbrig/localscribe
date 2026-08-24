package dev.chaseallbright.localscribe.domain

import dev.chaseallbright.localscribe.bridge.LlamaBridge
import java.io.File

/**
 * Loads the configured Qwen2.5 GGUF model if present and valid, otherwise falls back to
 * [RuleBasedCleaner] -- cleanup must never block dictation on a model that failed to load.
 * The owner must [close] it to free the native llama context. After [close], [clean] degrades
 * to the rule-based cleaner rather than touching the released native context.
 */
class AutoCleaner(modelPath: String?, contextSize: Int = 2048, threads: Int = 4) : Cleaner, AutoCloseable {
    private val bridge: LlamaBridge?
    private val delegate: Cleaner
    @Volatile private var closed = false

    /** True when the Qwen LLM backend is loaded; false when this instance has degraded to
     *  [RuleBasedCleaner] (model missing, invalid, or failed to load). Lets a resident-model
     *  owner (see ModelSessionEngine) treat a degraded instance as retryable rather than
     *  caching the fallback for the whole residency. */
    val isLlmLoaded: Boolean get() = bridge != null

    init {
        bridge = modelPath
            ?.let { path -> if (File(path).isFile) path else null }
            ?.let { path -> runCatching { LlamaBridge.load(path, contextSize, threads) }.getOrNull() }

        delegate = if (bridge != null) QwenCleaner(bridge::generate) else RuleBasedCleaner()
    }

    override fun clean(text: String, mode: CleanupMode, vocabulary: List<String>): CleanResult =
        if (closed) RuleBasedCleaner().clean(text, mode, vocabulary)
        else delegate.clean(text, mode, vocabulary)

    override fun close() {
        closed = true
        bridge?.release()
    }
}
