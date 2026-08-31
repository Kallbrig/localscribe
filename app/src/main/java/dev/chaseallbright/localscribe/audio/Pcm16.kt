package dev.chaseallbright.localscribe.audio

/** Decodes 16-bit little-endian mono PCM into normalised floats in [-1, 1]. */
object Pcm16 {

    private const val FULL_SCALE = 32768.0f

    /**
     * Decodes [chunks] in order and empties the list as it goes, so each chunk becomes
     * collectable while decoding continues. Peak memory is therefore one shrinking copy of the
     * input plus the output, not both at full size.
     *
     * Every chunk must hold whole 2-byte frames; [AudioRecorder] guarantees this by only ever
     * storing even byte counts. A trailing odd byte would be dropped.
     */
    fun drainToFloats(chunks: MutableList<ByteArray>): FloatArray {
        val totalBytes = chunks.sumOf { it.size }
        val samples = FloatArray(totalBytes / 2)
        var out = 0
        for (i in chunks.indices) {
            val chunk = chunks[i]
            var j = 0
            while (j + 1 < chunk.size) {
                val lo = chunk[j].toInt() and 0xFF
                val hi = chunk[j + 1].toInt()
                samples[out++] = ((hi shl 8) or lo) / FULL_SCALE
                j += 2
            }
            // Drop the reference now rather than at the end of the loop, so a long recording's
            // chunks are collectable while the remaining ones are still being decoded.
            chunks[i] = EMPTY
        }
        chunks.clear()
        return samples
    }

    private val EMPTY = ByteArray(0)
}
