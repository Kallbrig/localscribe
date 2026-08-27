package dev.chaseallbright.localscribe.backup

/**
 * What the user has agreed to send to Android's backup transport (Google Drive, or a
 * device-to-device transfer).
 *
 * This exists because `android:allowBackup="true"` with no rules is a silent opt-in: the
 * platform uploads app-private storage by default, which for this app meant every transcript
 * ever dictated went to Drive -- flatly contradicting what the app promises.
 *
 * Every category therefore starts off. Backup only happens because the user asked for it,
 * and onboarding puts the choice in front of them rather than leaving it buried in Settings.
 */
data class BackupChoices(
    /** Master switch. When false nothing is backed up, whatever the other flags say. */
    val enabled: Boolean = false,
    /** Cleanup style, model tier, and these backup choices themselves. Not sensitive. */
    val settings: Boolean = false,
    /** Custom vocabulary -- names and jargon. Mildly sensitive; useful to keep across devices. */
    val vocabulary: Boolean = false,
    /** Transcript history: the literal text of everything ever dictated. */
    val transcripts: Boolean = false
)

/** A single thing the backup agent can be asked to include. */
enum class BackupItem {
    /** SharedPreferences. */
    SETTINGS,

    /** A generated vocabulary-only export, used when vocabulary is wanted but transcripts are not. */
    VOCABULARY_EXPORT,

    /** The Room database file, which carries transcripts *and* vocabulary together. */
    DATABASE
}

/**
 * Turns [BackupChoices] into the concrete set of things to back up.
 *
 * The one non-obvious rule: transcripts and vocabulary live in the same Room database, so
 * they cannot be separated by file. Including transcripts therefore includes the whole
 * database (vocabulary comes along), and the separate vocabulary export is only generated
 * when vocabulary is wanted *without* transcripts. Models are never backed up -- they are
 * hundreds of megabytes and re-downloadable.
 */
object BackupPlan {

    fun itemsFor(choices: BackupChoices): Set<BackupItem> {
        if (!choices.enabled) return emptySet()

        val items = mutableSetOf<BackupItem>()
        if (choices.settings) items += BackupItem.SETTINGS
        if (choices.transcripts) {
            items += BackupItem.DATABASE
        } else if (choices.vocabulary) {
            items += BackupItem.VOCABULARY_EXPORT
        }
        return items
    }

    /** True when the transcript text itself would leave the device. Drives the warning copy. */
    fun sendsTranscripts(choices: BackupChoices): Boolean =
        BackupItem.DATABASE in itemsFor(choices)
}
