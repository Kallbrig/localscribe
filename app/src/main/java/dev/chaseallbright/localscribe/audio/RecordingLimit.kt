package dev.chaseallbright.localscribe.audio

/**
 * Selectable ceilings on a single recording.
 *
 * The limit exists because [AudioRecorder] buffers PCM in memory: at 32,000 bytes/sec an
 * unstopped recording exhausts the Java heap and takes the foreground service down with it.
 *
 * The notches are deliberately uneven. The useful range is short -- the cleanup model sees
 * about 2.5 minutes of speech at once -- so the resolution belongs at the low end, and the
 * longer notches exist for the rare deliberate long dictation rather than for tuning.
 */
enum class RecordingLimit(val id: String, val minutes: Int) {
    ONE("1m", 1),
    TWO("2m", 2),
    THREE("3m", 3),
    FIVE("5m", 5),
    TEN("10m", 10);

    /** Bytes of 16 kHz mono PCM16 this limit allows. */
    val bytes: Long
        get() = minutes.toLong() * SECONDS_PER_MINUTE *
            AudioRecorder.SAMPLE_RATE_HZ * BYTES_PER_SAMPLE

    /**
     * Long limits cost real processing time and degrade cleanup quality, so the user confirms
     * them rather than sliding into them.
     */
    val requiresConfirmation: Boolean
        get() = minutes >= CONFIRM_FROM_MINUTES

    val displayName: String
        get() = if (minutes == 1) "1 minute" else "$minutes minutes"

    companion object {
        val DEFAULT = TWO

        /** Bytes per PCM16 mono sample. */
        const val BYTES_PER_SAMPLE = 2
        private const val SECONDS_PER_MINUTE = 60
        private const val CONFIRM_FROM_MINUTES = 5
    }
}
