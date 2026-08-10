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

/**
 * The on-demand rewrite of the speaker's own message (web-feature port).
 *
 * Unlike validation this is never part of the pipeline — it costs a request, so
 * it starts [Idle] and only leaves that state when the user asks.
 *
 * Re-running the rewrite in one of the offered directions stays inside
 * [Success] with [Success.reloadingDirection] set, rather than dropping back to
 * [Loading]: the rewrite already on screen keeps its place and only the pill
 * that was tapped spins.
 */
sealed interface RewriteState {
    object Idle : RewriteState
    object Loading : RewriteState
    data class Success(
        val direction: String,
        val rewrittenSource: String,
        val alternativeDirections: List<String>,
        val reloadingDirection: String? = null,
    ) : RewriteState
    data class Error(val message: String) : RewriteState
}

data class ConversationDraft(
    val isLanguageA: Boolean,
    val sourceText: String,
    val translatedText: String = "",
    val roundTripText: String? = null,
    val isTranslating: Boolean = false,
    val validationState: ValidationState = ValidationState.Idle,
    /**
     * Identifies *this* draft so a slow round-trip or validation result cannot land
     * on a later one. The patches used to be `_conversationDraft.value?.copy(...)`,
     * which patches whatever draft happens to be current — harmless while a person
     * could only speak one turn at a time, not harmless once turns also arrive from
     * a peer (issue #33, finding #5).
     */
    val id: Long = nextDraftId(),
)

private var draftCounter = 0L

private fun nextDraftId(): Long = ++draftCounter
