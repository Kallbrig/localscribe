package dev.chaseallbright.localscribe.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TranscriptEntity::class, VocabularyEntity::class], version = 1, exportSchema = false)
abstract class LocalScribeDatabase : RoomDatabase() {
    abstract fun transcriptDao(): TranscriptDao
    abstract fun vocabularyDao(): VocabularyDao

    companion object {
        @Volatile private var instance: LocalScribeDatabase? = null

        fun getInstance(context: Context): LocalScribeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocalScribeDatabase::class.java,
                    "localscribe.db"
                ).build().also { instance = it }
            }
    }
}
