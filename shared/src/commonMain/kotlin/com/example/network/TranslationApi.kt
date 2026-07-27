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
 *
 * [sanityCheck] and [idiomExplanation] are ported from the web build's
 * `api/translate` response. They ride on this call rather than getting one of
 * their own, so they cost nothing; both default to absent, because a model that
 * ignores the added schema keys must still parse.
 */
data class ValidationResult(
    val isWarning: Boolean,
    val explanation: String,
    val suggestions: List<Suggestion>,
    /**
     * How the target-language speaker will actually perceive the translation,
     * in prose, written in the source language. Distinct from the literal
     * round-trip the pipeline gets for free from Google: that shows the words
     * coming back, this shows the intent landing.
     */
    val sanityCheck: String = "",
    /** The idiom/slang in the original explained, plus the native equivalent. */
    val idiomExplanation: String? = null,
)
