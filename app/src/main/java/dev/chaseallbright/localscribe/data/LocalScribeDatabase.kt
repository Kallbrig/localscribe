package dev.chaseallbright.localscribe.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TranscriptEntity::class, VocabularyEntity::class], version = 3, exportSchema = false)
abstract class LocalScribeDatabase : RoomDatabase() {
    abstract fun transcriptDao(): TranscriptDao
    abstract fun vocabularyDao(): VocabularyDao

    companion object {
        /** Referenced by the backup agent, which needs the file path without opening Room. */
        const val NAME = "localscribe.db"

        @Volatile private var instance: LocalScribeDatabase? = null

        /** v1 -> v2: transcripts learn which cleaner backend produced them. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transcripts ADD COLUMN cleanupBackend TEXT NOT NULL DEFAULT 'UNKNOWN'"
                )
            }
        }

        /** v2 -> v3: transcripts record how long each stage took, so history can show it. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcripts ADD COLUMN transcribeMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transcripts ADD COLUMN cleanupMillis INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): LocalScribeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocalScribeDatabase::class.java,
                    NAME
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
