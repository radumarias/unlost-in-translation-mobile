package com.example.ui.viewmodel.fakes

import com.example.data.TranslationHistoryRepository
import com.example.model.Language
import com.example.model.Translation
import com.example.model.TranslationContext
import com.example.network.TranslationApi
import com.example.network.ValidationResult
import com.example.platform.AppLog
import com.example.wear.TranslationBroadcaster
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Test fakes for the shared view model (design §10). Deterministic by default:
 * the API fakes echo their inputs so assertions can pin exact strings, and each
 * behavior is overridable per-test via the `on*` lambdas (e.g. to throw, or to
 * suspend on a gate for ordering tests).
 */
class FakeTranslationApi : TranslationApi {

    val googleCalls = mutableListOf<Triple<String, String, String>>()
    val geminiCalls = mutableListOf<Triple<String, String, String>>()
    val validateCalls = mutableListOf<Pair<String, String>>()

    /** The [TranslationContext] of each Gemini translate / validate call, in order. */
    val geminiContexts = mutableListOf<TranslationContext>()
    val validateContexts = mutableListOf<TranslationContext>()

    var onGoogle: suspend (text: String, sourceLang: String, targetLang: String) -> String =
        { text, _, _ -> "T($text)" }
    var onGemini: suspend (text: String, sourceLangName: String, targetLangName: String) -> String =
        { text, _, _ -> "G($text)" }
    var onValidate: suspend (originalText: String, translatedText: String) -> ValidationResult =
        { _, _ -> ValidationResult(isWarning = false, explanation = "Looks fine.", suggestions = emptyList()) }

    override suspend fun translateGoogle(text: String, sourceLang: String, targetLang: String): String {
        googleCalls += Triple(text, sourceLang, targetLang)
        return onGoogle(text, sourceLang, targetLang)
    }

    override suspend fun translateGemini(
        text: String,
        sourceLangName: String,
        targetLangName: String,
        context: TranslationContext,
    ): String {
        geminiCalls += Triple(text, sourceLangName, targetLangName)
        geminiContexts += context
        return onGemini(text, sourceLangName, targetLangName)
    }

    override suspend fun validateTranslation(
        originalText: String,
        translatedText: String,
        sourceLangName: String,
        targetLangName: String,
        context: TranslationContext,
    ): ValidationResult {
        validateCalls += originalText to translatedText
        validateContexts += context
        return onValidate(originalText, translatedText)
    }
}

/**
 * In-memory [TranslationHistoryRepository] mirroring the real implementations'
 * semantics: newest-first by timestamp, ids from 1, and the `timestamp == 0L`
 * "now" sentinel materialized — with a deterministic fake clock instead of a
 * real one, so ordering assertions can't flake.
 */
class FakeHistoryRepository : TranslationHistoryRepository {

    private val state = MutableStateFlow<List<Translation>>(emptyList())
    private var nextId = 1L
    private var fakeNowMillis = 1_000L

    /** Current rows, newest first — for direct assertions. */
    val rows: List<Translation> get() = state.value

    override fun allTranslations(): Flow<List<Translation>> = state

    override fun starredTranslations(): Flow<List<Translation>> =
        state.map { list -> list.filter { it.isStarred } }

    override suspend fun insert(translation: Translation): Long {
        val id = if (translation.id != 0L) translation.id else nextId++
        val timestamp = if (translation.timestamp == 0L) {
            fakeNowMillis += 1_000
            fakeNowMillis
        } else {
            translation.timestamp
        }
        val row = translation.copy(id = id, timestamp = timestamp)
        state.value = (listOf(row) + state.value.filterNot { it.id == id })
            .sortedByDescending { it.timestamp }
        return id
    }

    override suspend fun setStarred(id: Long, isStarred: Boolean) {
        state.value = state.value.map { if (it.id == id) it.copy(isStarred = isStarred) else it }
    }

    override suspend fun deleteById(id: Long) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun clearHistory() {
        state.value = emptyList()
    }
}

class RecordingBroadcaster : TranslationBroadcaster {
    data class Broadcast(
        val originalText: String,
        val translatedText: String,
        val sourceLang: Language,
        val targetLang: Language,
    )

    val broadcasts = mutableListOf<Broadcast>()

    override fun broadcast(originalText: String, translatedText: String, sourceLang: Language, targetLang: Language) {
        broadcasts += Broadcast(originalText, translatedText, sourceLang, targetLang)
    }
}

class RecordingLog : AppLog {
    val debugs = mutableListOf<String>()
    val errors = mutableListOf<String>()

    override fun d(tag: String, message: String) {
        debugs += "$tag: $message"
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        errors += "$tag: $message"
    }
}
