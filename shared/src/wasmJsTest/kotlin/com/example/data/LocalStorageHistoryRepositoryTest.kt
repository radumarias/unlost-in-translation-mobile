package com.example.data

import com.example.model.Translation
import com.example.ui.viewmodel.fakes.RecordingLog
import kotlinx.browser.localStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * wasmJsTest suite (design §10): localStorage round-trip and corrupt-data
 * recovery for the web history repository. Runs in a headless browser, so the
 * real `localStorage` is exercised, not a fake.
 */
class LocalStorageHistoryRepositoryTest {

    private val storageKey = "translation_history_v1"

    private fun translation(
        source: String,
        translated: String = "$source-es",
        starred: Boolean = false,
        timestamp: Long = 0L,
    ) = Translation(
        sourceText = source,
        translatedText = translated,
        sourceLangCode = "en",
        sourceLangName = "English",
        targetLangCode = "es",
        targetLangName = "Spanish",
        timestamp = timestamp,
        isStarred = starred,
    )

    private val nextIdKey = "translation_history_next_id_v1"

    @BeforeTest
    fun clearStorage() {
        localStorage.removeItem(storageKey)
        localStorage.removeItem(nextIdKey)
    }

    @Test
    fun roundTrip_insertedHistorySurvivesANewRepositoryInstance() = runTest {
        val log = RecordingLog()
        val writer = LocalStorageHistoryRepository(log)
        val firstId = writer.insert(translation("hello", timestamp = 1_000L))
        val secondId = writer.insert(translation("world", starred = true, timestamp = 2_000L))

        // A fresh instance must reload everything from localStorage.
        val reader = LocalStorageHistoryRepository(log)
        val all = reader.allTranslations().first()

        assertEquals(2, all.size)
        // Newest first, ids assigned sequentially from 1.
        assertEquals(listOf("world", "hello"), all.map { it.sourceText })
        assertEquals(listOf(secondId, firstId), all.map { it.id })
        assertEquals(listOf(1L, 2L), listOf(firstId, secondId))
        assertTrue(all.first().isStarred)
        assertEquals(listOf("world"), reader.starredTranslations().first().map { it.sourceText })
        assertTrue(log.errors.isEmpty(), "no errors expected, got: ${log.errors}")
    }

    @Test
    fun rowsWrittenInTheSameMillisecondKeepAStableNewestFirstOrder() = runTest {
        // Matches the Room DAO's `ORDER BY timestamp DESC, id DESC`. Without the
        // id tiebreak the two platforms could order the same history differently.
        val log = RecordingLog()
        val writer = LocalStorageHistoryRepository(log)
        val first = writer.insert(translation("first", timestamp = 5_000L))
        val second = writer.insert(translation("second", timestamp = 5_000L))
        val third = writer.insert(translation("third", timestamp = 5_000L))

        assertEquals(listOf(third, second, first), writer.allTranslations().first().map { it.id })
        // …and after a reload from storage, which sorts on a separate path.
        assertEquals(
            listOf("third", "second", "first"),
            LocalStorageHistoryRepository(log).allTranslations().first().map { it.sourceText },
        )
    }

    @Test
    fun roundTrip_mutationsPersistAcrossInstances() = runTest {
        val log = RecordingLog()
        val writer = LocalStorageHistoryRepository(log)
        val keepId = writer.insert(translation("keep", timestamp = 1_000L))
        val dropId = writer.insert(translation("drop", timestamp = 2_000L))
        writer.setStarred(keepId, true)
        writer.deleteById(dropId)

        val reader = LocalStorageHistoryRepository(log)
        val all = reader.allTranslations().first()

        assertEquals(listOf("keep"), all.map { it.sourceText })
        assertTrue(all.single().isStarred)
    }

    @Test
    fun clearHistory_removesStarredRowsToo() = runTest {
        val log = RecordingLog()
        val repository = LocalStorageHistoryRepository(log)
        repository.insert(translation("a", starred = true, timestamp = 1_000L))
        repository.insert(translation("b", timestamp = 2_000L))

        repository.clearHistory()

        assertEquals(emptyList(), LocalStorageHistoryRepository(log).allTranslations().first())
    }

    @Test
    fun corruptData_yieldsEmptyHistoryAndLogsInsteadOfCrashing() = runTest {
        localStorage.setItem(storageKey, "{not valid json][")
        val log = RecordingLog()

        val repository = LocalStorageHistoryRepository(log)

        assertEquals(emptyList(), repository.allTranslations().first())
        assertEquals(1, log.errors.size, "corrupt payload must be logged")
        // Recovery also drops the corrupt payload so later instances start clean.
        assertEquals(null, localStorage.getItem(storageKey))
        // The repository must still be writable after recovery.
        repository.insert(translation("fresh", timestamp = 1_000L))
        assertEquals(
            listOf("fresh"),
            LocalStorageHistoryRepository(log).allTranslations().first().map { it.sourceText },
        )
    }

    @Test
    fun anIdIsNeverHandedOutTwice_evenAfterTheRowIsDeleted() = runTest {
        val repository = LocalStorageHistoryRepository(RecordingLog())
        val first = repository.insert(translation("one", timestamp = 1_000L))

        repository.deleteById(first)
        val second = repository.insert(translation("two", timestamp = 2_000L))

        // `max(id) + 1` over the surviving rows handed the deleted row's id to the
        // next one, so anything still holding it — an open card, a pending star —
        // pointed at a different translation.
        assertTrue(second > first, "id $second was reused after $first was deleted")
    }

    @Test
    fun theIdCounterSurvivesAReload() = runTest {
        val log = RecordingLog()
        val first = LocalStorageHistoryRepository(log).insert(translation("one", timestamp = 1_000L))
        LocalStorageHistoryRepository(log).deleteById(first)

        val second = LocalStorageHistoryRepository(log).insert(translation("two", timestamp = 2_000L))

        assertTrue(second > first, "id $second was reused across a reload")
    }

    @Test
    fun anExistingInstallWithNoCounterStillMovesForward() = runTest {
        // Upgrading into this: rows exist, the counter key does not. The first id
        // afterwards still has to clear everything already stored.
        val log = RecordingLog()
        val seeded = LocalStorageHistoryRepository(log)
        seeded.insert(translation("one", timestamp = 1_000L))
        val highest = seeded.allTranslations().first().maxOf { it.id }
        localStorage.removeItem(nextIdKey)

        val next = LocalStorageHistoryRepository(log).insert(translation("two", timestamp = 2_000L))

        assertTrue(next > highest, "id $next collides with the stored $highest")
    }

    @Test
    fun structurallyValidButIncompleteRow_triggersCorruptRecoveryNotBlankRows() = runTest {
        // Valid JSON array, but the row is missing required fields (sourceText etc.).
        localStorage.setItem(storageKey, """[{"id":1,"isStarred":false}]""")
        val log = RecordingLog()

        val repository = LocalStorageHistoryRepository(log)

        // Must recover to empty (and log), not render a blank/garbage history row.
        assertEquals(emptyList(), repository.allTranslations().first())
        assertEquals(1, log.errors.size, "incomplete row must be treated as corrupt")
    }
}
