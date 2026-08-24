package dev.chaseallbright.localscribe.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TranscriptEntity::class, VocabularyEntity::class], version = 2, exportSchema = false)
abstract class LocalScribeDatabase : RoomDatabase() {
    abstract fun transcriptDao(): TranscriptDao
    abstract fun vocabularyDao(): VocabularyDao

    companion object {
        @Volatile private var instance: LocalScribeDatabase? = null

        /** v1 -> v2: transcripts learn which cleaner backend produced them. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transcripts ADD COLUMN cleanupBackend TEXT NOT NULL DEFAULT 'UNKNOWN'"
                )
            }
        }

        fun getInstance(context: Context): LocalScribeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocalScribeDatabase::class.java,
                    "localscribe.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
