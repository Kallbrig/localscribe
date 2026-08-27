package dev.chaseallbright.localscribe.backup

import android.content.Context
import android.util.Log
import dev.chaseallbright.localscribe.data.LocalScribeDatabase
import dev.chaseallbright.localscribe.data.VocabularyEntity

/**
 * Imports a vocabulary export left behind by a restore.
 *
 * The file arrives before the app has run, so nothing can consume it at restore time -- Room
 * may not even have created its database yet. Instead the file simply sits there and the next
 * launch picks it up, merges it, and deletes it.
 *
 * Merging rather than replacing: a restore should not throw away words added on this device
 * before the backup arrived. `VocabularyDao.insert` ignores conflicts, so re-running this is
 * harmless.
 */
object VocabularyRestore {

    suspend fun importIfPresent(context: Context): Int {
        val export = LocalScribeBackupAgent.vocabularyExportFile(context)
        if (!export.isFile) return 0

        val imported = runCatching {
            val dao = LocalScribeDatabase.getInstance(context).vocabularyDao()
            val existing = dao.getAllWords().map { it.lowercase() }.toSet()
            val words = export.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.lowercase() !in existing }
                .distinctBy { it.lowercase() }

            words.forEach { dao.insert(VocabularyEntity(word = it)) }
            words.size
        }.getOrElse {
            Log.w(TAG, "Could not import restored vocabulary", it)
            0
        }

        // Delete either way: a file that cannot be parsed will not parse next launch either,
        // and leaving it would retry forever.
        export.delete()
        if (imported > 0) Log.i(TAG, "Imported $imported restored vocabulary words")
        return imported
    }

    private const val TAG = "LocalScribeBackup"
}
