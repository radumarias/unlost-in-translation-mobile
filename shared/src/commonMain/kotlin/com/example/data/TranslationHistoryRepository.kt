package com.example.data

import com.example.model.Translation
import kotlinx.coroutines.flow.Flow

/**
 * History + starred storage seam for the view model (design §4), now in
 * commonMain and expressed in terms of the domain [Translation] model. The
 * Android impl is Room-backed; a web build supplies a localStorage impl.
 */
interface TranslationHistoryRepository {
    fun allTranslations(): Flow<List<Translation>>
    fun starredTranslations(): Flow<List<Translation>>
    /** Inserts (or replaces) and returns the row id. [Translation.timestamp] of 0 means "now". */
    suspend fun insert(translation: Translation): Long
    suspend fun setStarred(id: Long, isStarred: Boolean)
    suspend fun deleteById(id: Long)
    suspend fun clearHistory()
}
