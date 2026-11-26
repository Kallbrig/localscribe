package dev.chaseallbright.localscribe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Settings offers only the modes that actually differ. Casual is retained as an enum value
 * because transcripts and imports name it, but it behaves exactly like Standard, so showing
 * both was two names for one choice.
 */
class CleanupModeSelectionTest {

    @Test
    fun `settings offers informal, standard and business but not casual`() {
        assertEquals(
            listOf(CleanupMode.INFORMAL, CleanupMode.STANDARD, CleanupMode.BUSINESS),
            CleanupMode.selectable
        )
        assertFalse(CleanupMode.CASUAL in CleanupMode.selectable)
    }

    @Test
    fun `a stored casual resolves to standard`() {
        assertEquals(CleanupMode.STANDARD, CleanupMode.forSettings(CleanupMode.CASUAL))
    }

    @Test
    fun `offered modes are returned unchanged`() {
        CleanupMode.selectable.forEach { mode ->
            assertEquals(mode, CleanupMode.forSettings(mode))
        }
    }

    @Test
    fun `an unset preference gets standard as the default`() {
        assertEquals(CleanupMode.STANDARD, CleanupMode.forSettings(null))
    }

    @Test
    fun `the retired mode still resolves from its stored name`() {
        // History and imported archives store the enum's name, not the display label, so the
        // entry must survive under "CASUAL" for those rows to keep resolving.
        assertEquals(CleanupMode.CASUAL, CleanupMode.valueOf("CASUAL"))
    }
}
