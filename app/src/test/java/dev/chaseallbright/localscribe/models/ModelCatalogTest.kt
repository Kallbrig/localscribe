package dev.chaseallbright.localscribe.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun `low RAM devices default to the tiny whisper model`() {
        assertEquals(WhisperModelTier.TINY_EN, WhisperModelTier.defaultFor(2.0))
    }

    @Test
    fun `mid RAM devices default to the base whisper model`() {
        assertEquals(WhisperModelTier.BASE_EN, WhisperModelTier.defaultFor(4.0))
    }

    @Test
    fun `high RAM devices still default to base, small stays an opt-in upgrade`() {
        assertEquals(WhisperModelTier.BASE_EN, WhisperModelTier.defaultFor(12.0))
    }

    @Test
    fun `cleanup model always defaults to the smaller 0point5B tier`() {
        assertEquals(CleanupModelTier.QWEN_0_5B, CleanupModelTier.defaultFor(2.0))
        assertEquals(CleanupModelTier.QWEN_0_5B, CleanupModelTier.defaultFor(16.0))
    }
}
