package dev.chaseallbright.localscribe.transfer

import android.content.Context
import android.net.Uri
import dev.chaseallbright.localscribe.BuildConfig
import dev.chaseallbright.localscribe.data.LocalScribeDatabase
import dev.chaseallbright.localscribe.data.VocabularyEntity
import dev.chaseallbright.localscribe.domain.UserFacingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Reads and writes archive files through the Storage Access Framework.
 *
 * The user picks the destination, so no storage permission is needed and the file lands
 * wherever they chose -- local storage, an SD card, or a folder they sync themselves. That
 * keeps a device-to-device move entirely under their control, with nothing passing through
 * Google's backup transport.
 */
object ArchiveIo {

    data class ImportResult(val transcriptsAdded: Int, val wordsAdded: Int, val skipped: Int)

    suspend fun export(context: Context, destination: Uri): Int = withContext(Dispatchers.IO) {
        val database = LocalScribeDatabase.getInstance(context)
        val transcripts = database.transcriptDao().search(query = "", limit = Int.MAX_VALUE).first()
        val vocabulary = database.vocabularyDao().getAllWords()

        val json = TranscriptArchive.encode(
            vocabulary = vocabulary,
            transcripts = transcripts,
            appVersion = BuildConfig.VERSION_NAME,
            exportedAtEpochMillis = System.currentTimeMillis()
        )
        context.contentResolver.openOutputStream(destination, "wt")
            ?.use { it.write(json.toByteArray()) }
            ?: throw UserFacingException("Could not open the chosen file for writing.")
        transcripts.size
    }

    suspend fun import(context: Context, source: Uri): ImportResult = withContext(Dispatchers.IO) {
        val json = context.contentResolver.openInputStream(source)
            ?.use { it.readBytes().decodeToString() }
            ?: throw UserFacingException("Could not open the chosen file.")

        val archive = TranscriptArchive.decode(json)
        val database = LocalScribeDatabase.getInstance(context)
        val transcriptDao = database.transcriptDao()
        val vocabularyDao = database.vocabularyDao()

        // Merge, never replace: importing must not discard anything already on this device.
        val existingTranscripts = transcriptDao.search(query = "", limit = Int.MAX_VALUE).first()
        val newTranscripts = TranscriptArchive.transcriptsToInsert(archive.transcripts, existingTranscripts)
        newTranscripts.forEach { transcriptDao.insert(it) }

        val newWords = TranscriptArchive.vocabularyToInsert(archive.vocabulary, vocabularyDao.getAllWords())
        newWords.forEach { vocabularyDao.insert(VocabularyEntity(word = it)) }

        ImportResult(newTranscripts.size, newWords.size, archive.skipped)
    }

    /** Suggested filename, dated so successive exports do not overwrite one another. */
    fun suggestedFileName(nowEpochMillis: Long = System.currentTimeMillis()): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(nowEpochMillis))
        return "localscribe-export-$stamp.json"
    }
}
