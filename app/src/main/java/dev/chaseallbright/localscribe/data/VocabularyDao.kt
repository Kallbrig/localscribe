package dev.chaseallbright.localscribe.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabularyDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: VocabularyEntity): Long

    @Delete
    suspend fun delete(entity: VocabularyEntity)

    @Query("SELECT * FROM vocabulary ORDER BY word COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<VocabularyEntity>>

    @Query("SELECT word FROM vocabulary")
    suspend fun getAllWords(): List<String>
}
