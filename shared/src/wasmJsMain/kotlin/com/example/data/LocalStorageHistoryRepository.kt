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

        /**
         * Highest id ever handed out, so ids are not reused after a delete.
         * Separate from the rows because the whole point is to outlive them:
         * `max(id) + 1` over the surviving rows hands the newest row's id to the
         * next one as soon as it is deleted, and anything still holding the old id
         * — an open card, a pending star — then points at a different translation.
         * Room's autoincrement never does this; the web store should not either.
         */
        const val NEXT_ID_KEY = "translation_history_next_id_v1"
        const val TAG = "LocalStorageHistory"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(TranslationSerializer)

    // Source of truth. Kept sorted newest-first so the flows can map directly.
    private val state = MutableStateFlow(load())

    // Seeded from the rows when the counter is absent — an existing install
    // upgrading into this, or a browser that dropped the key — so the first id
    // after the upgrade is still higher than everything already stored.
    private var nextId: Long =
        maxOf(
            localStorage[NEXT_ID_KEY]?.toLongOrNull() ?: 0L,
            (state.value.maxOfOrNull { it.id } ?: 0L) + 1L,
        )

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
        // Write first, publish second: if the write fails (quota, privacy mode)
        // the visible history stays consistent with what will survive a reload,
        // instead of showing a mutation that silently vanishes next session.
        try {
            localStorage[STORAGE_KEY] = json.encodeToString(listSerializer, list)
        } catch (e: Exception) {
            log.e(TAG, "Failed writing history to localStorage; change not applied", e)
            return
        }
        state.value = list
    }

    /**
     * The next id, advanced and persisted before it is used. A failed write is not
     * fatal here — worst case the counter is re-seeded from the rows on the next
     * load, which is exactly where it started.
     */
    private fun claimId(): Long {
        val id = nextId
        nextId = id + 1
        try {
            localStorage[NEXT_ID_KEY] = nextId.toString()
        } catch (e: Exception) {
            log.e(TAG, "Failed writing the id counter to localStorage", e)
        }
        return id
    }

    override fun allTranslations(): Flow<List<Translation>> = state

    override fun starredTranslations(): Flow<List<Translation>> =
        state.map { list -> list.filter { it.isStarred } }

    override suspend fun insert(translation: Translation): Long {
        val current = state.value
        val id = if (translation.id != 0L) translation.id else claimId()
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
