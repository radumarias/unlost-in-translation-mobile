package com.example.data

import com.example.model.Translation
import com.example.platform.AppLog
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

    private class RecordingLog : AppLog {
        val errors = mutableListOf<String>()
        override fun d(tag: String, message: String) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {
            errors += "$tag: $message"
        }
    }

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

    @BeforeTest
    fun clearStorage() {
        localStorage.removeItem(storageKey)
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
