package com.example.ui.viewmodel

/**
 * UI-state and domain models for translation, shared across platforms (Phase 2).
 * Pure Kotlin — no Android or Compose dependency — extracted from
 * TranslateViewModel so the network layer and the (still Android-side) view model
 * can both live against them from commonMain. Names/package are unchanged.
 */

sealed interface TranslationState {
    object Idle : TranslationState
    object Loading : TranslationState
    data class Success(val originalText: String, val translatedText: String, val roundTripText: String? = null) : TranslationState
    data class Error(val message: String) : TranslationState
}

data class Suggestion(val tone: String, val text: String)

sealed interface ValidationState {
    object Idle : ValidationState
    object Loading : ValidationState
    data class Success(
        val isWarning: Boolean,
        val explanation: String,
        val suggestions: List<Suggestion>,
        /** How the target speaker will perceive it, in the source language. */
        val sanityCheck: String = "",
        /** The idiom in the original explained, with its native equivalent. */
        val idiomExplanation: String? = null,
    ) : ValidationState
    data class Error(val message: String) : ValidationState
}

data class ConversationDraft(
    val isLanguageA: Boolean,
    val sourceText: String,
    val translatedText: String = "",
    val roundTripText: String? = null,
    val isTranslating: Boolean = false,
    val validationState: ValidationState = ValidationState.Idle
)
