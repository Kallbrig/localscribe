package dev.chaseallbright.localscribe.domain

/**
 * Which cleaner actually produced a transcript's cleaned text.
 *
 * VERBATIM is informal mode working as designed -- it never runs the LLM -- and so is not a
 * degradation and must never be surfaced as one. RULES_FALLBACK means the LLM ran and its
 * output was rejected. UNKNOWN exists only for history rows written before this field did.
 */
enum class CleanupBackend { QWEN, VERBATIM, RULES, RULES_FALLBACK, UNKNOWN }

data class CleanResult(val text: String, val backend: CleanupBackend)
