package com.example.data

import com.example.model.Translation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed [TranslationHistoryRepository]. Maps the Room [TranslationEntity]
 * to/from the shared domain [Translation]. Stays in :app until the Room layer
 * moves to shared/androidMain.
 */
class RoomTranslationHistoryRepository(
    private val dao: TranslationDao,
) : TranslationHistoryRepository {

    override fun allTranslations(): Flow<List<Translation>> =
        dao.getAllTranslations().map { list -> list.map { it.toDomain() } }

    override fun starredTranslations(): Flow<List<Translation>> =
        dao.getStarredTranslations().map { list -> list.map { it.toDomain() } }

    override suspend fun insert(translation: Translation): Long =
        dao.insertTranslation(translation.toEntity())

    override suspend fun setStarred(id: Long, isStarred: Boolean) =
        dao.updateStarredState(id, isStarred)

    override suspend fun deleteById(id: Long) = dao.deleteById(id)

    override suspend fun clearHistory() = dao.clearHistory()
}

private fun TranslationEntity.toDomain(): Translation = Translation(
    id = id,
    sourceText = sourceText,
    translatedText = translatedText,
    sourceLangCode = sourceLangCode,
    sourceLangName = sourceLangName,
    targetLangCode = targetLangCode,
    targetLangName = targetLangName,
    timestamp = timestamp,
    isStarred = isStarred,
)

/**
 * Maps a domain [Translation] to a Room entity. A [Translation.timestamp] of 0
 * (the "now" sentinel for new inserts) falls back to the entity's own
 * `System.currentTimeMillis()` default, preserving the previous insert behavior.
 */
private fun Translation.toEntity(): TranslationEntity =
    if (timestamp == 0L) {
        TranslationEntity(
            id = id,
            sourceText = sourceText,
            translatedText = translatedText,
            sourceLangCode = sourceLangCode,
            sourceLangName = sourceLangName,
            targetLangCode = targetLangCode,
            targetLangName = targetLangName,
            isStarred = isStarred,
        )
    } else {
        TranslationEntity(
            id = id,
            sourceText = sourceText,
            translatedText = translatedText,
            sourceLangCode = sourceLangCode,
            sourceLangName = sourceLangName,
            targetLangCode = targetLangCode,
            targetLangName = targetLangName,
            timestamp = timestamp,
            isStarred = isStarred,
        )
    }
