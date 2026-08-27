package dev.chaseallbright.localscribe.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPlanTest {

    @Test
    fun `nothing at all is backed up by default`() {
        val defaults = BackupChoices()

        assertFalse("the master switch starts off", defaults.enabled)
        assertFalse(defaults.settings)
        assertFalse(defaults.vocabulary)
        assertFalse(defaults.transcripts)
        assertEquals(
            "Android backs up by default; this app must not until asked",
            emptySet<BackupItem>(),
            BackupPlan.itemsFor(defaults)
        )
        assertFalse(BackupPlan.sendsTranscripts(defaults))
    }

    @Test
    fun `the master switch overrides every category`() {
        val choices = BackupChoices(enabled = false, settings = true, vocabulary = true, transcripts = true)

        assertEquals(emptySet<BackupItem>(), BackupPlan.itemsFor(choices))
        assertFalse(BackupPlan.sendsTranscripts(choices))
    }

    @Test
    fun `vocabulary without transcripts uses the generated export, not the database`() {
        val items = BackupPlan.itemsFor(
            BackupChoices(enabled = true, vocabulary = true, transcripts = false, settings = false)
        )

        assertEquals(
            "the database carries transcripts too, so it must not be the vehicle here",
            setOf(BackupItem.VOCABULARY_EXPORT),
            items
        )
    }

    @Test
    fun `transcripts pull in the whole database and make the separate export redundant`() {
        val items = BackupPlan.itemsFor(
            BackupChoices(enabled = true, vocabulary = true, transcripts = true, settings = false)
        )

        assertEquals(setOf(BackupItem.DATABASE), items)
        assertFalse(
            "vocabulary is already inside the database; exporting it twice is wasted",
            BackupItem.VOCABULARY_EXPORT in items
        )
    }

    @Test
    fun `transcripts without vocabulary still take the database because they share a file`() {
        val items = BackupPlan.itemsFor(
            BackupChoices(enabled = true, vocabulary = false, transcripts = true, settings = false)
        )

        assertEquals(setOf(BackupItem.DATABASE), items)
        assertTrue(
            "the user should be told vocabulary rides along, since it cannot be excluded",
            BackupPlan.sendsTranscripts(BackupChoices(enabled = true, vocabulary = false, transcripts = true))
        )
    }

    @Test
    fun `settings alone backs up nothing else`() {
        assertEquals(
            setOf(BackupItem.SETTINGS),
            BackupPlan.itemsFor(BackupChoices(enabled = true, settings = true, vocabulary = false, transcripts = false))
        )
    }

    @Test
    fun `everything off but enabled produces an empty plan`() {
        assertEquals(
            emptySet<BackupItem>(),
            BackupPlan.itemsFor(
                BackupChoices(enabled = true, settings = false, vocabulary = false, transcripts = false)
            )
        )
    }

    @Test
    fun `models are never an option`() {
        val everything = BackupChoices(enabled = true, settings = true, vocabulary = true, transcripts = true)

        assertEquals(
            "models are hundreds of MB and re-downloadable; they must never enter a backup",
            setOf(BackupItem.SETTINGS, BackupItem.DATABASE),
            BackupPlan.itemsFor(everything)
        )
    }

    @Test
    fun `sendsTranscripts is true only when the database actually goes`() {
        assertTrue(BackupPlan.sendsTranscripts(BackupChoices(enabled = true, transcripts = true)))
        assertFalse(BackupPlan.sendsTranscripts(BackupChoices(enabled = true, transcripts = false, vocabulary = true)))
        assertFalse(BackupPlan.sendsTranscripts(BackupChoices(enabled = false, transcripts = true)))
    }
}
