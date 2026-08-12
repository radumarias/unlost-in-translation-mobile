package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationDao {
    // `id DESC` is not decoration: two rows written in the same millisecond
    // (a translation and its conversation counterpart, a fast retry) would
    // otherwise come back in whatever order SQLite felt like, and the history
    // list would reshuffle between reads. Matches the web repository's ordering.
    @Query("SELECT * FROM translations ORDER BY timestamp DESC, id DESC")
    fun getAllTranslations(): Flow<List<TranslationEntity>>

    @Query("SELECT * FROM translations WHERE isStarred = 1 ORDER BY timestamp DESC, id DESC")
    fun getStarredTranslations(): Flow<List<TranslationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranslation(translation: TranslationEntity): Long

    @Query("UPDATE translations SET isStarred = :isStarred WHERE id = :id")
    suspend fun updateStarredState(id: Long, isStarred: Boolean)

    @Query("DELETE FROM translations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM translations")
    suspend fun clearHistory()
}
