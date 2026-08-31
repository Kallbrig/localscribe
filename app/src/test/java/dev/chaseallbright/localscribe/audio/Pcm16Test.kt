package dev.chaseallbright.localscribe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16Test {

    /** Little-endian PCM16 bytes for the given samples. */
    private fun bytesOf(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, sample ->
            out[i * 2] = (sample and 0xFF).toByte()
            out[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `empty input decodes to an empty array`() {
        assertEquals(0, Pcm16.drainToFloats(mutableListOf()).size)
    }

    @Test
    fun `silence decodes to zero`() {
        val floats = Pcm16.drainToFloats(mutableListOf(bytesOf(0, 0)))
        assertEquals(2, floats.size)
        assertEquals(0f, floats[0], 0f)
        assertEquals(0f, floats[1], 0f)
    }

    @Test
    fun `positive and negative samples normalise into minus one to one`() {
        val floats = Pcm16.drainToFloats(mutableListOf(bytesOf(16384, -16384, 32767, -32768)))
        assertEquals(0.5f, floats[0], 1e-6f)
        assertEquals(-0.5f, floats[1], 1e-6f)
        assertEquals(0.99997f, floats[2], 1e-4f)
        assertEquals(-1.0f, floats[3], 1e-6f)
    }

    @Test
    fun `samples spanning several chunks stay in capture order`() {
        val chunks = mutableListOf(bytesOf(100, 200), bytesOf(300), bytesOf(400, 500))
        val floats = Pcm16.drainToFloats(chunks)
        assertEquals(5, floats.size)
        listOf(100, 200, 300, 400, 500).forEachIndexed { i, sample ->
            assertEquals(sample / 32768.0f, floats[i], 1e-6f)
        }
    }

    @Test
    fun `the chunk list is emptied so the encoded bytes can be collected`() {
        val chunks = mutableListOf(bytesOf(1, 2), bytesOf(3, 4))
        Pcm16.drainToFloats(chunks)
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `an empty chunk among real ones is skipped`() {
        val chunks = mutableListOf(bytesOf(100), ByteArray(0), bytesOf(200))
        val floats = Pcm16.drainToFloats(chunks)
        assertEquals(2, floats.size)
        assertEquals(100 / 32768.0f, floats[0], 1e-6f)
        assertEquals(200 / 32768.0f, floats[1], 1e-6f)
    }
}
