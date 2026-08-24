package dev.chaseallbright.localscribe.domain

/**
 * The user chose LLM cleanup by installing a model; this decides when they should be told
 * they silently got the rules cleaner instead. RULES_FALLBACK always warns -- the LLM ran and
 * its output was rejected. Plain RULES only warns if a cleanup model is actually installed
 * (otherwise rules-only is the expected, un-warned default). QWEN and UNKNOWN never warn.
 */
fun shouldWarnCleanupFallback(backend: CleanupBackend, cleanupModelInstalled: Boolean): Boolean =
    when (backend) {
        CleanupBackend.RULES_FALLBACK -> true
        CleanupBackend.RULES -> cleanupModelInstalled
        else -> false
    }
