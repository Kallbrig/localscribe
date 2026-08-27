package dev.chaseallbright.localscribe.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.chaseallbright.localscribe.data.LocalScribeDatabase
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Decides, at backup time, what actually leaves the device.
 *
 * The XML backup rules Android offers are static, so they cannot express a user setting.
 * Overriding [onFullBackup] and calling [fullBackupFile] per file can: nothing is included
 * except what this method explicitly hands over, so the user's choices are enforced rather
 * than merely declared.
 *
 * This runs for both cloud backup and device-to-device transfer. They are treated the same,
 * on the grounds that a user who does not want their transcripts on Google's servers
 * probably also wants to make that choice consciously when moving phones.
 */
class LocalScribeBackupAgent : BackupAgent() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        val choices = BackupSettings(this).choices
        val items = BackupPlan.itemsFor(choices)
        Log.i(TAG, "Backup requested; including $items")

        // Deliberately no super.onFullBackup() call: the default implementation sweeps up all
        // app-private storage, which is the behaviour being fixed.
        if (items.isEmpty()) return

        if (BackupItem.SETTINGS in items) {
            backUpSharedPreferences(data)
        }
        if (BackupItem.DATABASE in items) {
            backUpDatabase(data)
        }
        if (BackupItem.VOCABULARY_EXPORT in items) {
            backUpVocabularyOnly(data)
        }
    }

    private fun backUpSharedPreferences(data: FullBackupDataOutput) {
        val prefsDir = File(applicationInfo.dataDir, "shared_prefs")
        prefsDir.listFiles()?.forEach { file ->
            runCatching { fullBackupFile(file, data) }
                .onFailure { Log.w(TAG, "Could not back up ${file.name}", it) }
        }
    }

    private fun backUpDatabase(data: FullBackupDataOutput) {
        // Fold the write-ahead log into the main file first, or recent dictations live only in
        // the -wal and the restored copy silently loses them.
        runCatching {
            LocalScribeDatabase.getInstance(this).openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }.onFailure { Log.w(TAG, "WAL checkpoint failed; backing up anyway", it) }

        val database = getDatabasePath(LocalScribeDatabase.NAME)
        if (database.isFile) fullBackupFile(database, data)
    }

    /**
     * Vocabulary was asked for but transcripts were not, and both live in the same database
     * file. Writing a vocabulary-only export at backup time keeps the two separable without
     * maintaining a second copy on disk the rest of the time.
     */
    private fun backUpVocabularyOnly(data: FullBackupDataOutput) {
        val export = vocabularyExportFile(this)
        runCatching {
            val words = runBlocking {
                LocalScribeDatabase.getInstance(this@LocalScribeBackupAgent)
                    .vocabularyDao().getAllWords()
            }
            export.parentFile?.mkdirs()
            export.writeText(words.joinToString("\n"))
        }.onFailure {
            Log.w(TAG, "Vocabulary export failed; skipping it", it)
            return
        }
        if (export.isFile) fullBackupFile(export, data)
    }

    // Key/value backup is unused -- this app is full-data only -- but BackupAgent requires them.
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?
    ) = Unit

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?
    ) = Unit


    companion object {
        private const val TAG = "LocalScribeBackup"

        /** Restored alongside other files; imported and deleted on next launch. */
        fun vocabularyExportFile(context: android.content.Context): File =
            File(File(context.filesDir, "backup"), "vocabulary.txt")

    }
}
