package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationDao {
    @Query("SELECT * FROM translations ORDER BY timestamp DESC")
    fun getAllTranslations(): Flow<List<TranslationEntity>>

    @Query("SELECT * FROM translations WHERE isStarred = 1 ORDER BY timestamp DESC")
    fun getStarredTranslations(): Flow<List<TranslationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranslation(translation: TranslationEntity): Long

    @Query("UPDATE translations SET isStarred = :isStarred WHERE id = :id")
    suspend fun updateStarredState(id: Long, isStarred: Boolean)

    @Delete
    suspend fun deleteTranslation(translation: TranslationEntity)

    @Query("DELETE FROM translations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM translations")
    suspend fun clearHistory()
}
