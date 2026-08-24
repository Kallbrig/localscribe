package dev.chaseallbright.localscribe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {
    @Insert
    suspend fun insert(entity: TranscriptEntity): Long

    @Query(
        """
        SELECT * FROM transcripts
        WHERE :query = '' OR raw LIKE '%' || :query || '%' OR cleaned LIKE '%' || :query || '%'
        ORDER BY id DESC
        LIMIT :limit
        """
    )
    fun search(query: String = "", limit: Int = 200): Flow<List<TranscriptEntity>>

    @Query("DELETE FROM transcripts")
    suspend fun clear()
}
