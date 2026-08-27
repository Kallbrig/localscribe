package dev.chaseallbright.localscribe.backup

import android.content.Context

/**
 * Persists the user's backup choices.
 *
 * Deliberately in its own SharedPreferences file rather than alongside app settings: the
 * backup agent reads this while the app may not be otherwise initialised, and keeping it
 * separate makes it obvious that turning off "settings" backup does not orphan the very
 * preference that says so -- this file rides along with SETTINGS.
 */
class BackupSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var choices: BackupChoices
        get() = BackupChoices(
            enabled = prefs.getBoolean(KEY_ENABLED, DEFAULTS.enabled),
            settings = prefs.getBoolean(KEY_SETTINGS, DEFAULTS.settings),
            vocabulary = prefs.getBoolean(KEY_VOCABULARY, DEFAULTS.vocabulary),
            transcripts = prefs.getBoolean(KEY_TRANSCRIPTS, DEFAULTS.transcripts)
        )
        set(value) = prefs.edit()
            .putBoolean(KEY_ENABLED, value.enabled)
            .putBoolean(KEY_SETTINGS, value.settings)
            .putBoolean(KEY_VOCABULARY, value.vocabulary)
            .putBoolean(KEY_TRANSCRIPTS, value.transcripts)
            .apply()

    companion object {
        const val PREFS_NAME = "localscribe_backup"

        /** Transcripts off: an upgrade must not silently start uploading dictation text. */
        private val DEFAULTS = BackupChoices()

        private const val KEY_ENABLED = "backup_enabled"
        private const val KEY_SETTINGS = "backup_settings"
        private const val KEY_VOCABULARY = "backup_vocabulary"
        private const val KEY_TRANSCRIPTS = "backup_transcripts"
    }
}
