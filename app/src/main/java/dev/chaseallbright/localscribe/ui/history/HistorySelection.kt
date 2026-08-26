package dev.chaseallbright.localscribe.ui.history

/**
 * Which transcripts are currently selected, kept as pure data so the multi-select rules are
 * testable without a device.
 *
 * Selection is by id rather than index because the list is a live Room [kotlinx.coroutines.flow.Flow]:
 * a new dictation arriving, or the search query changing, reorders and re-filters it underneath
 * the user. Indices would silently select the wrong rows; ids cannot.
 */
@JvmInline
value class HistorySelection(val ids: Set<Long> = emptySet()) {

    /** Selection mode is on precisely when something is selected -- there is no separate flag. */
    val isActive: Boolean get() = ids.isNotEmpty()

    val count: Int get() = ids.size

    operator fun contains(id: Long): Boolean = id in ids

    fun toggle(id: Long): HistorySelection =
        HistorySelection(if (id in ids) ids - id else ids + id)

    fun selectAll(visible: List<Long>): HistorySelection = HistorySelection(ids + visible)

    fun clear(): HistorySelection = HistorySelection(emptySet())

    /** True when every currently visible row is selected, so the toggle-all control can flip. */
    fun coversAll(visible: List<Long>): Boolean = visible.isNotEmpty() && ids.containsAll(visible)

    /**
     * Drops ids that are no longer on screen. Without this, selecting rows and then typing a
     * search query would leave hidden rows selected and a bulk delete would remove transcripts
     * the user could not see.
     */
    fun retaining(visible: Collection<Long>): HistorySelection {
        val kept = ids intersect visible.toSet()
        return if (kept.size == ids.size) this else HistorySelection(kept)
    }
}
