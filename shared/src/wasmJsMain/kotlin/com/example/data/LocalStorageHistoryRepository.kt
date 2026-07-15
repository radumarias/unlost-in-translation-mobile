package com.example.data

import com.example.model.Translation
import com.example.model.TranslationSerializer
import com.example.platform.AppLog
import kotlinx.browser.localStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.w3c.dom.get
import org.w3c.dom.set
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Web [TranslationHistoryRepository] backed by `localStorage` (design §7).
 *
 * A single key holds the whole history as a JSON array of [Translation]. An
 * in-memory [MutableStateFlow] is the source of truth for the observe* flows;
 * every mutation rewrites the key. The list is loaded once at construction;
 * absent or corrupt data yields an empty list (logged, never a crash). Ordering
 * and filtering mirror the Room queries: newest first, starred filtered from the
 * same list. `id` is assigned as max(existing)+1. Single-tab; cross-tab sync is
 * out of scope.
 */
class LocalStorageHistoryRepository(
    private val log: AppLog,
) : TranslationHistoryRepository {

    private companion object {
        const val STORAGE_KEY = "translation_history_v1"
        const val TAG = "LocalStorageHistory"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(TranslationSerializer)

    // Source of truth. Kept sorted newest-first so the flows can map directly.
    private val state = MutableStateFlow(load())

    private fun load(): List<Translation> {
        val raw = localStorage[STORAGE_KEY] ?: return emptyList()
        return try {
            json.decodeFromString(listSerializer, raw).sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            log.e(TAG, "Corrupt history in localStorage; starting empty", e)
            // Drop the corrupt payload so it isn't re-parsed (and re-logged) by
            // every future instance; recovery is "start empty", not "keep bad bytes".
            localStorage.removeItem(STORAGE_KEY)
            emptyList()
        }
    }

    private fun persist(list: List<Translation>) {
        state.value = list
        try {
            localStorage[STORAGE_KEY] = json.encodeToString(listSerializer, list)
        } catch (e: Exception) {
            log.e(TAG, "Failed writing history to localStorage", e)
        }
    }

    override fun allTranslations(): Flow<List<Translation>> = state

    override fun starredTranslations(): Flow<List<Translation>> =
        state.map { list -> list.filter { it.isStarred } }

    override suspend fun insert(translation: Translation): Long {
        val current = state.value
        val id = if (translation.id != 0L) translation.id else (current.maxOfOrNull { it.id } ?: 0L) + 1L
        val timestamp = if (translation.timestamp == 0L) nowMillis() else translation.timestamp
        val row = translation.copy(id = id, timestamp = timestamp)
        // Newest first; replace any existing row with the same id.
        val next = (listOf(row) + current.filterNot { it.id == id })
            .sortedByDescending { it.timestamp }
        persist(next)
        return id
    }

    override suspend fun setStarred(id: Long, isStarred: Boolean) {
        persist(state.value.map { if (it.id == id) it.copy(isStarred = isStarred) else it })
    }

    override suspend fun deleteById(id: Long) {
        persist(state.value.filterNot { it.id == id })
    }

    override suspend fun clearHistory() {
        // Matches the Room semantics: clears everything, including starred.
        persist(emptyList())
    }
}

/** Wall-clock epoch milliseconds — same source the shared view model uses. */
@OptIn(ExperimentalTime::class)
private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
