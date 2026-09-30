package dev.chaseallbright.localscribe.ui.overlay

/**
 * How long dragging the bubble onto the X hides it. A timed choice hides it for every field
 * until the time is up; after that the bubble waits for the next field to gain focus rather than
 * reappearing in one already in use.
 */
enum class DismissDuration(val id: String, val minutes: Int?) {
    UNTIL_NEXT_FIELD("next_field", null),
    ONE("1m", 1),
    FIVE("5m", 5),
    FIFTEEN("15m", 15),
    THIRTY("30m", 30),
    SIXTY("60m", 60);

    /** Wall-clock deadline, or null when only the next field focus matters. */
    fun deadlineFrom(nowMillis: Long): Long? = minutes?.let { nowMillis + it * MILLIS_PER_MINUTE }

    val displayName: String
        get() = when (minutes) {
            null -> "Until next text field"
            1 -> "1 minute"
            60 -> "1 hour"
            else -> "$minutes minutes"
        }

    companion object {
        val DEFAULT = UNTIL_NEXT_FIELD
        private const val MILLIS_PER_MINUTE = 60_000L
        private val LONGEST_MILLIS = entries.mapNotNull { it.minutes }.max() * MILLIS_PER_MINUTE

        /**
         * The deadline is wall-clock so it survives the process dying. A deadline further away
         * than the longest option can only mean the clock moved backwards; honouring it would
         * stretch an hour's dismissal indefinitely, so it counts as expired.
         */
        fun isSuppressed(nowMillis: Long, deadlineMillis: Long): Boolean {
            val remaining = deadlineMillis - nowMillis
            return remaining in 1..LONGEST_MILLIS
        }
    }
}
