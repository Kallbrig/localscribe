package dev.chaseallbright.localscribe.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import dev.chaseallbright.localscribe.domain.CleanupBackend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Runs the real migrations against real SQLite, then opens the database through Room so its
 * schema validation is exercised too. A migration that produces a schema Room rejects fails
 * here rather than on a user's first launch after an update.
 *
 * The v1 schema is written by hand because it predates schema export: `exportSchema = false`
 * means there is no historical JSON for MigrationTestHelper to build from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalScribeDatabaseMigrationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val databaseName = "migration-test.db"

    private val migrations = arrayOf(
        LocalScribeDatabase.MIGRATION_1_2,
        LocalScribeDatabase.MIGRATION_2_3,
        LocalScribeDatabase.MIGRATION_3_4
    )

    @Before
    fun removeDatabaseBefore() {
        context.deleteDatabase(databaseName)
    }

    @After
    fun removeDatabaseAfter() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `v1 transcripts reach the current schema with the new columns defaulted`() {
        createVersion1(modes = listOf("STANDARD"))

        withCurrent { database ->
            val rows = runBlocking { database.transcriptDao().search().first() }

            assertEquals("the row must survive four versions", 1, rows.size)
            val row = rows.single()
            assertEquals("cleaned text", row.cleaned)
            assertEquals(CleanupBackend.UNKNOWN.name, row.cleanupBackend)
            assertEquals(0L, row.transcribeMillis)
            assertEquals(0L, row.cleanupMillis)
        }
    }

    @Test
    fun `v1 vocabulary survives`() {
        createVersion1(modes = emptyList(), words = listOf("Kallbrig", "LocalScribe"))

        withCurrent { database ->
            assertEquals(
                listOf("Kallbrig", "LocalScribe"),
                runBlocking { database.vocabularyDao().getAllWords() }.sorted()
            )
        }
    }

    @Test
    fun `retired mode names are folded into standard and informal is untouched`() {
        createVersion1(modes = listOf("INFORMAL", "STANDARD", "CASUAL", "BUSINESS"))

        withCurrent { database ->
            val modes = runBlocking { database.transcriptDao().search().first() }
                .sortedBy { it.id }
                .map { it.mode }

            assertEquals(listOf("INFORMAL", "STANDARD", "STANDARD", "STANDARD"), modes)
        }
    }

    @Test
    fun `mode rewriting keeps the transcript text intact`() {
        createVersion1(modes = listOf("BUSINESS"))

        withCurrent { database ->
            val row = runBlocking { database.transcriptDao().search().first() }.single()

            assertEquals("STANDARD", row.mode)
            assertEquals("raw text", row.raw)
            assertEquals("cleaned text", row.cleaned)
        }
    }

    /** Creates a database at version 1, the schema every subsequent migration starts from. */
    private fun createVersion1(modes: List<String>, words: List<String> = emptyList()) {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(CREATE_V1_TRANSCRIPTS)
                    db.execSQL(CREATE_V1_VOCABULARY)
                    modes.forEach { mode ->
                        db.execSQL(
                            INSERT_V1_TRANSCRIPT,
                            arrayOf<Any?>(123L, "raw text", "cleaned text", "en", 1.0, mode)
                        )
                    }
                    words.forEach { word ->
                        db.execSQL("INSERT INTO vocabulary (word) VALUES (?)", arrayOf(word))
                    }
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        // Touching writableDatabase is what makes SQLiteOpenHelper call onCreate.
        helper.writableDatabase
        helper.close()
    }

    private fun openCurrent(): LocalScribeDatabase =
        Room.databaseBuilder(context, LocalScribeDatabase::class.java, databaseName)
            .addMigrations(*migrations)
            // The test thread is the main looper under Robolectric, and Room refuses main
            // thread queries without this.
            .allowMainThreadQueries()
            .build()

    /** RoomDatabase is AutoCloseable, not Closeable, so stdlib `use` does not apply. */
    private inline fun <T> withCurrent(block: (LocalScribeDatabase) -> T): T {
        val database = openCurrent()
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    private companion object {
        val CREATE_V1_TRANSCRIPTS = """
            CREATE TABLE IF NOT EXISTS `transcripts` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                `raw` TEXT NOT NULL,
                `cleaned` TEXT NOT NULL,
                `language` TEXT NOT NULL,
                `durationSeconds` REAL NOT NULL,
                `mode` TEXT NOT NULL
            )
        """.trimIndent()

        val CREATE_V1_VOCABULARY = """
            CREATE TABLE IF NOT EXISTS `vocabulary` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `word` TEXT NOT NULL
            )
        """.trimIndent()

        const val INSERT_V1_TRANSCRIPT =
            "INSERT INTO transcripts " +
                "(createdAtEpochMillis, raw, cleaned, language, durationSeconds, mode) " +
                "VALUES (?, ?, ?, ?, ?, ?)"
    }
}
