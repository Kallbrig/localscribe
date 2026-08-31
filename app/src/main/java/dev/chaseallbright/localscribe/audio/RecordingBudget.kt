package dev.chaseallbright.localscribe.audio

/**
 * Running byte budget for one recording.
 *
 * The recorder consults this on every read, so the capture buffer cannot exceed its limit. A
 * timer would bound elapsed time rather than memory, and would leave the buffer unbounded on
 * any occasion it failed to fire; enforcing on the write path makes overflow unrepresentable.
 *
 * Not thread-safe. The recording thread is the only caller.
 */
class RecordingBudget(private val limitBytes: Long) {

    init {
        require(limitBytes > 0) { "Recording limit must be positive, was $limitBytes" }
    }

    var usedBytes: Long = 0L
        private set

    val isFull: Boolean
        get() = usedBytes >= limitBytes

    /**
     * Records that [count] bytes were read and returns how many of them may be kept. The chunk
     * straddling the limit is truncated to the exact remainder; every chunk after it returns 0.
     */
    fun accept(count: Int): Int {
        if (count <= 0) return 0
        val remaining = limitBytes - usedBytes
        if (remaining <= 0) return 0
        val accepted = minOf(count.toLong(), remaining).toInt()
        usedBytes += accepted
        return accepted
    }
}
