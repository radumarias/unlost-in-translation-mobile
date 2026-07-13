package com.example.data

import kotlinx.coroutines.flow.Flow

/**
 * History + starred storage seam for the view model (Phase 1 task 5). Lets the
 * view model depend on an interface instead of Room's [TranslationDao] directly,
 * so a web build can supply a `LocalStorage`-backed implementation later
 * (design §4). [RoomTranslationHistoryRepository] is the Android/Room impl.
 */
interface TranslationHistoryRepository {
    fun allTranslations(): Flow<List<TranslationEntity>>
    fun starredTranslations(): Flow<List<TranslationEntity>>
    suspend fun insert(entity: TranslationEntity): Long
    suspend fun setStarred(id: Long, isStarred: Boolean)
    suspend fun deleteById(id: Long)
    suspend fun clearHistory()
}

/** Room-backed [TranslationHistoryRepository]; a thin delegation to [TranslationDao]. */
class RoomTranslationHistoryRepository(
    private val dao: TranslationDao,
) : TranslationHistoryRepository {
    override fun allTranslations(): Flow<List<TranslationEntity>> = dao.getAllTranslations()
    override fun starredTranslations(): Flow<List<TranslationEntity>> = dao.getStarredTranslations()
    override suspend fun insert(entity: TranslationEntity): Long = dao.insertTranslation(entity)
    override suspend fun setStarred(id: Long, isStarred: Boolean) = dao.updateStarredState(id, isStarred)
    override suspend fun deleteById(id: Long) = dao.deleteById(id)
    override suspend fun clearHistory() = dao.clearHistory()
}
