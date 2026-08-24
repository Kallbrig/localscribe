package dev.chaseallbright.localscribe.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vocabulary")
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String
)
