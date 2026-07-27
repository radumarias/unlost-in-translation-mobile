package com.example.network

import com.example.model.TranslationContext
import com.example.ui.viewmodel.Suggestion

/**
 * Translation + cultural-validation backend. Introduced in Phase 1 task 4 so the
 * view model depends on an interface rather than the concrete OkHttp/org.json
 * object; [KtorTranslationApi] is the Ktor + kotlinx.serialization implementation.
 * Moves to commonMain when `:shared` is created (design §3).
 *
 * The Gemini-backed calls take a [TranslationContext] (tone, situation, prior
 * turns) ported from the web build. It only ever becomes prompt text, so it
 * costs no extra requests; [translateGoogle] has no equivalent because the free
 * endpoint takes nothing but the string.
 */
interface TranslationApi {
    suspend fun translateGoogle(text: String, sourceLang: String, targetLang: String): String

    suspend fun translateGemini(
        text: String,
        sourceLangName: String,
        targetLangName: String,
        context: TranslationContext = TranslationContext(),
    ): String

    suspend fun validateTranslation(
        originalText: String,
        translatedText: String,
        sourceLangName: String,
        targetLangName: String,
        context: TranslationContext = TranslationContext(),
    ): ValidationResult
}

/**
 * Parsed result of a Gemini cultural-validation call. Replaces the raw
 * `JSONObject` the service used to hand back; the lenient parsing rules now live
 * in [GeminiParser] and are kept bug-for-bug.
 */
data class ValidationResult(
    val isWarning: Boolean,
    val explanation: String,
    val suggestions: List<Suggestion>,
)
