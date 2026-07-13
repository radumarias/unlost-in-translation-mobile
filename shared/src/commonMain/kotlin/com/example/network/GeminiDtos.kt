package com.example.network

import com.example.ui.viewmodel.Suggestion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Lenient JSON: unknown keys ignored (Gemini/Google add fields we don't model). */
val LenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

// --- Gemini request DTOs (contents / safetySettings / generationConfig) ---

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val safetySettings: List<GeminiSafetySetting>,
    val generationConfig: GeminiGenerationConfig? = null,
)

@Serializable
data class GeminiContent(val parts: List<GeminiPart>)

@Serializable
data class GeminiPart(val text: String)

@Serializable
data class GeminiSafetySetting(val category: String, val threshold: String)

@Serializable
data class GeminiGenerationConfig(
    @SerialName("responseMimeType") val responseMimeType: String,
)

// --- Gemini response DTO ---

@Serializable
data class GeminiResponse(val candidates: List<GeminiCandidate> = emptyList())

@Serializable
data class GeminiCandidate(val content: GeminiContent)

/** The four safety categories, all set to BLOCK_NONE — verbatim from the service. */
val GEMINI_SAFETY_SETTINGS: List<GeminiSafetySetting> = listOf(
    "HARM_CATEGORY_HARASSMENT",
    "HARM_CATEGORY_HATE_SPEECH",
    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
    "HARM_CATEGORY_DANGEROUS_CONTENT",
).map { GeminiSafetySetting(it, "BLOCK_NONE") }

/**
 * Parses the free Google Translate (`translate_a/single`) body. The outer array's
 * first element is a list of `[translatedPart, ...]` groups; concatenate element
 * 0 of each group, skipping empty groups — identical to the previous org.json
 * loop, now via kotlinx.serialization's [JsonElement] tree (design §5).
 */
object GoogleResponseParser {
    fun extract(responseBody: String): String {
        val outer = LenientJson.parseToJsonElement(responseBody).jsonArray
        val firstGroup = outer[0].jsonArray
        val sb = StringBuilder()
        for (part in firstGroup) {
            val arr = part.jsonArray
            if (arr.isNotEmpty()) {
                // element 0 is the translated text; matches getString(0)
                arr[0].jsonPrimitive.contentOrNull?.let { sb.append(it) }
            }
        }
        return sb.toString()
    }
}

/**
 * Parses Gemini's cultural-validation payload. Kept bug-for-bug with the old
 * org.json code: strips an optional Markdown ```json fence, then reads
 * `isWarning` (default false), `explanation` (default ""), and `suggestions`
 * where each element is either an object (`{"tone","text"}`, tone default
 * "Alternative") or a plain string (tone "Alternative"); blank-text entries are
 * dropped (design §3).
 */
object GeminiParser {

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

    /** Parses the fenced-or-bare validation text into a [ValidationResult]. */
    fun parseValidation(rawText: String): ValidationResult {
        val inner = stripJsonFences(rawText)
        val root = LenientJson.parseToJsonElement(inner).jsonObject
        return parseValidationObject(root)
    }

    /** Parses an already-decoded validation object (used by tests + fixtures). */
    fun parseValidationObject(root: JsonObject): ValidationResult {
        val isWarning = (root["isWarning"] as? JsonPrimitive)?.booleanOrNull ?: false
        val explanation = (root["explanation"] as? JsonPrimitive)?.contentOrNull ?: ""
        val suggestions = mutableListOf<Suggestion>()
        (root["suggestions"] as? JsonArray)?.forEach { element ->
            when (element) {
                is JsonObject -> {
                    val tone = (element["tone"] as? JsonPrimitive)?.contentOrNull ?: "Alternative"
                    val text = (element["text"] as? JsonPrimitive)?.contentOrNull ?: ""
                    if (text.isNotBlank()) suggestions.add(Suggestion(tone, text))
                }
                is JsonPrimitive -> {
                    val plain = element.contentOrNull ?: ""
                    if (plain.isNotBlank()) suggestions.add(Suggestion("Alternative", plain))
                }
                else -> { /* nested arrays etc. are ignored, as optJSONObject/optString did */ }
            }
        }
        return ValidationResult(isWarning, explanation, suggestions)
    }
}
