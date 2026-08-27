package dev.chaseallbright.localscribe.transfer

import dev.chaseallbright.localscribe.data.TranscriptEntity
import dev.chaseallbright.localscribe.domain.CleanupBackend
import dev.chaseallbright.localscribe.domain.CleanupMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * The on-disk format for a manual export, and the rules for reading one back.
 *
 * JSON rather than CSV, deliberately: a transcript is arbitrary dictated text containing
 * commas, quotes and newlines, and every one of those is a CSV escaping hazard. A file that
 * round-trips only until someone dictates a comma is worse than no export at all.
 *
 * The envelope is versioned so a future format change can be detected and refused rather
 * than silently mis-parsed.
 */
object TranscriptArchive {

    const val FORMAT = "localscribe-export"
    const val VERSION = 1
    const val MIME_TYPE = "application/json"

    /** What a decode produced, plus anything that had to be skipped. */
    data class Archive(
        val vocabulary: List<String> = emptyList(),
        val transcripts: List<TranscriptEntity> = emptyList(),
        val skipped: Int = 0
    )

    class UnsupportedArchive(message: String) : IllegalArgumentException(message)

    fun encode(
        vocabulary: List<String>,
        transcripts: List<TranscriptEntity>,
        appVersion: String,
        exportedAtEpochMillis: Long
    ): String {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("appVersion", appVersion)
            .put("exportedAt", exportedAtEpochMillis)
            .put("vocabulary", JSONArray(vocabulary))

        val array = JSONArray()
        transcripts.forEach { entity ->
            array.put(
                JSONObject()
                    .put("createdAt", entity.createdAtEpochMillis)
                    .put("raw", entity.raw)
                    .put("cleaned", entity.cleaned)
                    .put("language", entity.language)
                    .put("durationSeconds", entity.durationSeconds.toDouble())
                    .put("mode", entity.mode)
                    .put("cleanupBackend", entity.cleanupBackend)
            )
        }
        return root.put("transcripts", array).toString(2)
    }

    /**
     * Reads an archive. A row referencing a mode or backend this build does not know is kept
     * rather than dropped -- the text is the valuable part, and an export from a newer build
     * should not lose transcripts just because an enum grew.
     */
    fun decode(json: String): Archive {
        val root = runCatching { JSONObject(json) }
            .getOrElse { throw UnsupportedArchive("This file is not a LocalScribe export.") }

        val format = root.optString("format")
        if (format != FORMAT) {
            throw UnsupportedArchive("This file is not a LocalScribe export.")
        }
        val version = root.optInt("version", -1)
        if (version > VERSION) {
            throw UnsupportedArchive(
                "This export was made by a newer version of LocalScribe (format $version). Update the app first."
            )
        }

        val vocabulary = root.optJSONArray("vocabulary")
            ?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) } }
            .orEmpty()

        var skipped = 0
        val transcripts = mutableListOf<TranscriptEntity>()
        val array = root.optJSONArray("transcripts")
        if (array != null) {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index)
                val cleaned = row?.optString("cleaned").orEmpty()
                val createdAt = row?.optLong("createdAt", 0L) ?: 0L
                if (row == null || cleaned.isBlank() || createdAt <= 0L) {
                    skipped++
                    continue
                }
                transcripts += TranscriptEntity(
                    createdAtEpochMillis = createdAt,
                    raw = row.optString("raw", cleaned),
                    cleaned = cleaned,
                    language = row.optString("language", "unknown"),
                    durationSeconds = row.optDouble("durationSeconds", 0.0).toFloat(),
                    mode = row.optString("mode").ifBlank { CleanupMode.STANDARD.name },
                    cleanupBackend = row.optString("cleanupBackend").ifBlank { CleanupBackend.UNKNOWN.name }
                )
            }
        }
        return Archive(vocabulary, transcripts, skipped)
    }

    /**
     * Identity for de-duplication on import. Ids are not usable -- they are per-device
     * autoincrement values -- so a transcript is the same one if it was recorded at the same
     * instant with the same cleaned text. Re-importing the same file is therefore a no-op
     * rather than a way to double your history.
     */
    fun identityOf(entity: TranscriptEntity): Pair<Long, String> =
        entity.createdAtEpochMillis to entity.cleaned

    /** Rows from [incoming] not already present in [existing]. */
    fun transcriptsToInsert(
        incoming: List<TranscriptEntity>,
        existing: List<TranscriptEntity>
    ): List<TranscriptEntity> {
        val seen = existing.mapTo(mutableSetOf(), ::identityOf)
        return incoming.filter { seen.add(identityOf(it)) }
    }

    /** Words from [incoming] not already known, compared case-insensitively. */
    fun vocabularyToInsert(incoming: List<String>, existing: List<String>): List<String> {
        val seen = existing.mapTo(mutableSetOf()) { it.lowercase() }
        return incoming.map { it.trim() }
            .filter { it.isNotEmpty() && seen.add(it.lowercase()) }
    }
}
