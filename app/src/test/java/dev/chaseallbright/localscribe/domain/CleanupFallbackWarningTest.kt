package dev.chaseallbright.localscribe.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives whether the service should toast "AI cleanup unavailable" -- RULES_FALLBACK always
 * warns (the LLM ran and was rejected), RULES only warns if the user actually installed a
 * cleanup model (otherwise rules-only is expected, not a fallback), and QWEN/UNKNOWN never warn.
 */
class CleanupFallbackWarningTest {

    @Test
    fun `RULES_FALLBACK warns regardless of whether a cleanup model is installed`() {
        assertTrue(shouldWarnCleanupFallback(CleanupBackend.RULES_FALLBACK, cleanupModelInstalled = true))
        assertTrue(shouldWarnCleanupFallback(CleanupBackend.RULES_FALLBACK, cleanupModelInstalled = false))
    }

    @Test
    fun `RULES warns only when a cleanup model is installed`() {
        assertTrue(shouldWarnCleanupFallback(CleanupBackend.RULES, cleanupModelInstalled = true))
        assertFalse(shouldWarnCleanupFallback(CleanupBackend.RULES, cleanupModelInstalled = false))
    }

    @Test
    fun `QWEN never warns`() {
        assertFalse(shouldWarnCleanupFallback(CleanupBackend.QWEN, cleanupModelInstalled = true))
        assertFalse(shouldWarnCleanupFallback(CleanupBackend.QWEN, cleanupModelInstalled = false))
    }

    @Test
    fun `UNKNOWN never warns`() {
        assertFalse(shouldWarnCleanupFallback(CleanupBackend.UNKNOWN, cleanupModelInstalled = true))
        assertFalse(shouldWarnCleanupFallback(CleanupBackend.UNKNOWN, cleanupModelInstalled = false))
    }
}
