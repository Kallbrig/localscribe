package dev.chaseallbright.localscribe.ui.overlay

/**
 * How long the mic bubble stays full-size before shrinking to a dot.
 *
 * Discrete notches rather than a continuous 0-60 s slider, for the same reason as
 * [dev.chaseallbright.localscribe.audio.RecordingLimit]: the useful resolution is at the short end,
 * and a one-second-per-pixel slider is hard to land on a value with a thumb.
 */
enum class CollapseDelay(val id: String, val seconds: Int?) {
    ZERO("0s", 0),
    ONE("1s", 1),
    TWO("2s", 2),
    THREE("3s", 3),
    FIVE("5s", 5),
    TEN("10s", 10),
    FIFTEEN("15s", 15),
    THIRTY("30s", 30),
    SIXTY("60s", 60),
    NEVER("never", null);

    /** Timer length, or null when the bubble never collapses. */
    val millis: Long?
        get() = seconds?.let { it * 1_000L }

    val displayName: String
        get() = when (seconds) {
            null -> "Never"
            0 -> "Immediately"
            1 -> "1 second"
            60 -> "1 minute"
            else -> "$seconds seconds"
        }

    companion object {
        val DEFAULT = THREE
    }
}
