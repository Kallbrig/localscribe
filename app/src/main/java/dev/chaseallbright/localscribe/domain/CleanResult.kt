package dev.chaseallbright.localscribe.domain

/**
 * Which cleaner actually produced a transcript's cleaned text: RULES_FALLBACK means the LLM
 * ran but its output was rejected, and UNKNOWN exists only for history rows written before
 * this field existed.
 */
enum class CleanupBackend { QWEN, RULES, RULES_FALLBACK, UNKNOWN }

data class CleanResult(val text: String, val backend: CleanupBackend)
