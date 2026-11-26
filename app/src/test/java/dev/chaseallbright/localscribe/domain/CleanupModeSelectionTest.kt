package dev.chaseallbright.localscribe.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two modes reach Settings. A name from another version resolves to the default instead of
 * becoming an invisible selection or failing to resolve.
 */
class CleanupModeSelectionTest {

    @Test
    fun `settings offers exactly informal and standard`() {
        assertEquals(listOf(CleanupMode.INFORMAL, CleanupMode.STANDARD), CleanupMode.entries)
    }

    @Test
    fun `a name this build does not offer resolves to standard`() {
        assertEquals(CleanupMode.STANDARD, CleanupMode.fromStoredName("SOME_RETIRED_MODE"))
        assertEquals(CleanupMode.STANDARD, CleanupMode.fromStoredName("SOME_FUTURE_MODE"))
    }

    @Test
    fun `an unset preference gets standard as the default`() {
        assertEquals(CleanupMode.STANDARD, CleanupMode.fromStoredName(null))
    }

    @Test
    fun `offered names resolve to themselves`() {
        CleanupMode.entries.forEach { mode ->
            assertEquals(mode, CleanupMode.fromStoredName(mode.name))
        }
    }
}
