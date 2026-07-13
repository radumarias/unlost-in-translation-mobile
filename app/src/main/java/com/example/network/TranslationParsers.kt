package com.example.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure parsing helpers for the raw endpoint payloads, split out of
 * [TranslationService] so they can be unit-tested without any network I/O
 * (Phase 1 task 3). The logic is a verbatim extraction of the org.json code
 * that previously lived inline in the service / view model; the observable
 * behavior is unchanged. These move to `commonMain` (as `GoogleResponseParser`
 * et al.) once `:shared` exists and org.json is replaced by kotlinx.serialization.
 */
object TranslationParsers {

    /**
     * Parses the free Google Translate (`translate_a/single`) response body: an
     * outer JSON array whose first element is an array of `[translatedPart, ...]`
     * groups. The translated parts (index 0 of each group) are concatenated in
     * order — this preserves multi-sentence responses, which Google splits into
     * several groups. Empty groups are skipped.
     */
    fun parseGoogleTranslation(responseBody: String): String {
        val outerArray = JSONArray(responseBody)
        val firstGroup = outerArray.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until firstGroup.length()) {
            val part = firstGroup.getJSONArray(i)
            if (part.length() > 0) {
                sb.append(part.getString(0))
            }
        }
        return sb.toString()
    }

    /**
     * Strips an optional Markdown code fence (```json … ``` or ``` … ```) that
     * Gemini sometimes wraps its JSON in, returning the trimmed inner text.
     * Mirrors the fence handling previously inline in
     * `validateTranslationGemini`.
     */
    fun stripJsonFences(rawText: String): String {
        var text = rawText.trim()
        if (text.startsWith("```json")) {
            text = text.removePrefix("```json")
        } else if (text.startsWith("```")) {
            text = text.removePrefix("```")
        }
        if (text.endsWith("```")) {
            text = text.removeSuffix("```")
        }
        return text.trim()
    }

    /**
     * Parses a Gemini cultural-validation JSON object into its components:
     * `isWarning` (default false), `explanation` (default ""), and the list of
     * tone/text suggestions. A suggestion element may be an object
     * (`{"tone","text"}`) or a plain string (treated as tone "Alternative").
     * Suggestions with a blank `text` are dropped. Extracted verbatim from
     * `TranslateViewModel`'s validation handling.
     */
    fun parseValidation(json: JSONObject): ParsedValidation {
        val isWarning = json.optBoolean("isWarning", false)
        val explanation = json.optString("explanation", "")
        val suggestions = mutableListOf<ParsedSuggestion>()
        val suggestionsArray = json.optJSONArray("suggestions")
        if (suggestionsArray != null) {
            for (i in 0 until suggestionsArray.length()) {
                val sugObj = suggestionsArray.optJSONObject(i)
                if (sugObj != null) {
                    val tone = sugObj.optString("tone", "Alternative")
                    val text = sugObj.optString("text", "")
                    if (text.isNotBlank()) {
                        suggestions.add(ParsedSuggestion(tone, text))
                    }
                } else {
                    // Fallback if it's a plain string
                    val plainString = suggestionsArray.optString(i)
                    if (plainString.isNotBlank()) {
                        suggestions.add(ParsedSuggestion("Alternative", plainString))
                    }
                }
            }
        }
        return ParsedValidation(isWarning, explanation, suggestions)
    }
}

/** Plain, dependency-free result of [TranslationParsers.parseValidation]. */
data class ParsedValidation(
    val isWarning: Boolean,
    val explanation: String,
    val suggestions: List<ParsedSuggestion>,
)

/** Plain tone/text suggestion; maps 1:1 to the view model's `Suggestion`. */
data class ParsedSuggestion(
    val tone: String,
    val text: String,
)
