package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.model.Translation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the real Room persistence layer — DAO queries, the entity mapping and
 * the [TranslationHistoryRepository] contract — which every view-model test
 * replaces with a fake. Counterpart to the web build's
 * `LocalStorageHistoryRepositoryTest`, and asserts the same behaviours so the
 * two platforms cannot drift.
 *
 * Lives in `:app` rather than `:shared` only because Robolectric is already
 * wired up here; the code under test is `shared/androidMain`.
 *
 * The database is built directly rather than through
 * [TranslationDatabase.getDatabase]: that caches a process-wide instance backed
 * by a real file, which the screenshot test also touches. In-memory keeps every
 * test isolated and leaves that singleton untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomTranslationHistoryRepositoryTest {

  private lateinit var database: TranslationDatabase
  private lateinit var repository: RoomTranslationHistoryRepository

  @Before
  fun createDatabase() {
    database = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext<Context>(),
      TranslationDatabase::class.java,
    ).allowMainThreadQueries().build()
    repository = RoomTranslationHistoryRepository(database.translationDao())
  }

  @After
  fun closeDatabase() {
    database.close()
  }

  private fun translation(
    source: String,
    translated: String = "$source-es",
    starred: Boolean = false,
    timestamp: Long = 1_000L,
    id: Long = 0L,
  ) = Translation(
    id = id,
    sourceText = source,
    translatedText = translated,
    sourceLangCode = "en",
    sourceLangName = "English",
    targetLangCode = "es",
    targetLangName = "Spanish",
    timestamp = timestamp,
    isStarred = starred,
  )

  @Test
  fun `insert assigns ascending ids and history comes back newest first`() = runTest {
    val oldest = repository.insert(translation("hello", timestamp = 1_000L))
    val newest = repository.insert(translation("world", timestamp = 2_000L))

    assertEquals(listOf(1L, 2L), listOf(oldest, newest))

    val all = repository.allTranslations().first()
    assertEquals(listOf("world", "hello"), all.map { it.sourceText })
    assertEquals(listOf(newest, oldest), all.map { it.id })
  }

  @Test
  fun `rows written in the same millisecond keep a stable newest-first order`() = runTest {
    // The realistic case: a translation and its conversation counterpart, or a
    // fast retry. Without the id tiebreaker SQLite may return either first and
    // the history list reshuffles between reads.
    val first = repository.insert(translation("first", timestamp = 5_000L))
    val second = repository.insert(translation("second", timestamp = 5_000L))
    val third = repository.insert(translation("third", timestamp = 5_000L))

    val all = repository.allTranslations().first()

    assertEquals(listOf(third, second, first), all.map { it.id })
    assertEquals(listOf("third", "second", "first"), all.map { it.sourceText })
  }

  @Test
  fun `every field survives the round trip through the entity`() = runTest {
    val id = repository.insert(
      Translation(
        sourceText = "good morning",
        translatedText = "buongiorno",
        sourceLangCode = "en",
        sourceLangName = "English",
        targetLangCode = "it",
        targetLangName = "Italian",
        timestamp = 1_700_000_000_000L,
        isStarred = true,
      )
    )

    val stored = repository.allTranslations().first().single()
    assertEquals(
      Translation(
        id = id,
        sourceText = "good morning",
        translatedText = "buongiorno",
        sourceLangCode = "en",
        sourceLangName = "English",
        targetLangCode = "it",
        targetLangName = "Italian",
        timestamp = 1_700_000_000_000L,
        isStarred = true,
      ),
      stored,
    )
  }

  @Test
  fun `a zero timestamp is stored as now, not as zero`() = runTest {
    val before = System.currentTimeMillis()

    repository.insert(translation("now", timestamp = 0L))

    val stored = repository.allTranslations().first().single()
    assertNotEquals(0L, stored.timestamp)
    assertTrue(
      "expected a clock value at or after $before, got ${stored.timestamp}",
      stored.timestamp >= before,
    )
  }

  @Test
  fun `setStarred toggles both ways and starred stays newest first`() = runTest {
    val older = repository.insert(translation("older", timestamp = 1_000L))
    val newer = repository.insert(translation("newer", timestamp = 2_000L))

    repository.setStarred(older, true)
    repository.setStarred(newer, true)
    assertEquals(listOf("newer", "older"), repository.starredTranslations().first().map { it.sourceText })

    repository.setStarred(newer, false)
    assertEquals(listOf("older"), repository.starredTranslations().first().map { it.sourceText })
    // Unstarring must not remove the row from the history itself.
    assertEquals(2, repository.allTranslations().first().size)
  }

  @Test
  fun `deleteById removes only that row`() = runTest {
    val keep = repository.insert(translation("keep", timestamp = 1_000L))
    val drop = repository.insert(translation("drop", timestamp = 2_000L))

    repository.deleteById(drop)

    val all = repository.allTranslations().first()
    assertEquals(listOf("keep"), all.map { it.sourceText })
    assertEquals(keep, all.single().id)
  }

  @Test
  fun `clearHistory removes starred rows too`() = runTest {
    repository.insert(translation("starred", starred = true, timestamp = 1_000L))
    repository.insert(translation("plain", timestamp = 2_000L))

    repository.clearHistory()

    assertEquals(emptyList<Translation>(), repository.allTranslations().first())
    assertEquals(emptyList<Translation>(), repository.starredTranslations().first())
  }

  @Test
  fun `inserting with an existing id replaces that row instead of duplicating it`() = runTest {
    val id = repository.insert(translation("first", timestamp = 1_000L))

    val replacedId = repository.insert(
      translation("second", starred = true, timestamp = 3_000L, id = id)
    )

    assertEquals(id, replacedId)
    val all = repository.allTranslations().first()
    assertEquals(listOf("second"), all.map { it.sourceText })
    assertTrue(all.single().isStarred)
  }
}
