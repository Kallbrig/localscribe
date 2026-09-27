package dev.chaseallbright.localscribe.ui.overlay

/**
 * Whether the idle bubble is shrunk to a dot, as a pure reducer.
 *
 * [generation] increments on every wake or expand. A collapse timer is started for one
 * generation and reports it back on expiry, so a timer that has since been superseded -- the user
 * focused another field while it was pending -- cannot collapse the bubble early. The same shape
 * as the recording limit's generation counter, for the same reason.
 *
 * Dragging deliberately does not restart the timer, but a timer expiring mid-drag is deferred to
 * the release: shrinking the target out from under the user's finger would be disorienting.
 */
data class BubbleCollapse(
    val collapsed: Boolean = false,
    val dragging: Boolean = false,
    val expiredDuringDrag: Boolean = false,
    val generation: Int = 0
) {
    sealed interface Event {
        /** A text field gained focus, or a dictation finished. */
        data object Wake : Event

        /** The user tapped the dot. */
        data object Expand : Event

        data class TimerExpired(val generation: Int) : Event
        data object DragStart : Event
        data object DragEnd : Event
    }

    fun reduce(event: Event): BubbleCollapse = when (event) {
        Event.Wake, Event.Expand -> copy(
            collapsed = false,
            expiredDuringDrag = false,
            generation = generation + 1
        )
        is Event.TimerExpired -> when {
            event.generation != generation -> this
            dragging -> copy(expiredDuringDrag = true)
            else -> copy(collapsed = true)
        }
        Event.DragStart -> copy(dragging = true)
        Event.DragEnd -> copy(
            dragging = false,
            collapsed = collapsed || expiredDuringDrag,
            expiredDuringDrag = false
        )
    }
}
