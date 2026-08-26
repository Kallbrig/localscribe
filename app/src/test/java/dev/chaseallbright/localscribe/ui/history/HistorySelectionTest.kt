package dev.chaseallbright.localscribe.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySelectionTest {

    @Test
    fun `starts empty and inactive`() {
        val selection = HistorySelection()

        assertFalse(selection.isActive)
        assertEquals(0, selection.count)
    }

    @Test
    fun `toggle selects then deselects`() {
        val selected = HistorySelection().toggle(7L)
        assertTrue(7L in selected)
        assertTrue(selected.isActive)

        val cleared = selected.toggle(7L)
        assertFalse(7L in cleared)
        assertFalse("deselecting the last row leaves selection mode", cleared.isActive)
    }

    @Test
    fun `selectAll adds every visible row without dropping existing ones`() {
        val selection = HistorySelection().toggle(99L).selectAll(listOf(1L, 2L, 3L))

        assertEquals(setOf(99L, 1L, 2L, 3L), selection.ids)
    }

    @Test
    fun `coversAll reflects whether the visible rows are all selected`() {
        val visible = listOf(1L, 2L)

        assertFalse(HistorySelection().coversAll(visible))
        assertFalse(HistorySelection().toggle(1L).coversAll(visible))
        assertTrue(HistorySelection().selectAll(visible).coversAll(visible))
    }

    @Test
    fun `coversAll is false for an empty list so select-all cannot appear on an empty screen`() {
        assertFalse(HistorySelection().coversAll(emptyList()))
    }

    @Test
    fun `retaining drops rows that scrolled out of the filtered list`() {
        val selection = HistorySelection().selectAll(listOf(1L, 2L, 3L))

        val afterSearch = selection.retaining(listOf(2L, 3L, 4L))

        assertEquals(
            "a row hidden by the search query must not stay selected for a bulk delete",
            setOf(2L, 3L),
            afterSearch.ids
        )
    }

    @Test
    fun `retaining returns the same instance when nothing changed`() {
        val selection = HistorySelection().selectAll(listOf(1L, 2L))

        assertEquals(selection, selection.retaining(listOf(1L, 2L, 3L)))
    }

    @Test
    fun `retaining everything away leaves selection mode`() {
        val selection = HistorySelection().selectAll(listOf(1L, 2L))

        assertFalse(selection.retaining(emptyList()).isActive)
    }

    @Test
    fun `clear empties the selection`() {
        assertFalse(HistorySelection().selectAll(listOf(1L, 2L)).clear().isActive)
    }
}
