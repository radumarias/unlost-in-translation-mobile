package com.example.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the observed behavior of the parsing extracted from the old
 * TranslationService / TranslateViewModel, re-implemented on
 * kotlinx.serialization (Phase 1 task 4). Fixtures are the same real response
 * shapes captured in task 3, inlined here so the test runs in commonTest with no
 * JVM classloader / resource lookup.
 */
class NetworkParsersTest {

    private fun validationObject(json: String) =
        GeminiParser.parseValidationObject(Json.parseToJsonElement(json).jsonObject)

    // --- Google translate_a/single ---

    @Test
    fun google_single_sentence() {
        assertEquals("Hola", GoogleResponseParser.extract(GOOGLE_SINGLE_SENTENCE))
    }

    @Test
    fun google_multi_sentence_concatenates_all_groups_in_order() {
        assertEquals(
            "Hola. ¿Cómo estás? Adiós.",
            GoogleResponseParser.extract(GOOGLE_MULTI_SENTENCE),
        )
    }

    @Test
    fun google_empty_first_group_yields_empty_string() {
        assertEquals("", GoogleResponseParser.extract("[[],null,\"en\"]"))
    }

    @Test
    fun google_skips_zero_length_parts() {
        // second group is an empty inner array and must be skipped, not crash
        assertEquals("A", GoogleResponseParser.extract("[[[\"A\",\"a\"],[]],null,\"en\"]"))
    }

    @Test
    fun google_empty_outer_array_is_a_parse_error_not_an_index_crash() {
        // the unofficial endpoint can change shape without notice; an empty body
        // must surface as SerializationException (error card), never IndexOutOfBounds
        assertFailsWith<SerializationException> { GoogleResponseParser.extract("[]") }
    }

    // --- Gemini error bodies (issue #3 honest errors) ---

    @Test
    fun error_parser_extracts_googles_message() {
        assertEquals(
            "This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later.",
            GeminiErrorParser.errorMessage(
                """{"error":{"code":503,"message":"This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later.","status":"UNAVAILABLE"}}""",
            ),
        )
    }

    @Test
    fun error_parser_returns_null_when_the_body_is_not_an_error_shape() {
        assertNull(GeminiErrorParser.errorMessage("upstream down"))
        assertNull(GeminiErrorParser.errorMessage("""{"something":"else"}"""))
        assertNull(GeminiErrorParser.errorMessage("""{"error":{"code":500}}"""))
    }

    // --- Gemini fence stripping ---

    @Test
    fun strip_json_fence() {
        val inner = GeminiParser.stripJsonFences(VALIDATION_FENCED)
        assertTrue(inner.startsWith("{"))
        assertTrue(inner.endsWith("}"))
    }

    @Test
    fun strip_bare_fence_without_json_tag() {
        assertEquals("{\"a\":1}", GeminiParser.stripJsonFences("```\n{\"a\":1}\n```"))
    }

    @Test
    fun strip_leaves_unfenced_text_unchanged_apart_from_trim() {
        assertEquals("{\"a\":1}", GeminiParser.stripJsonFences("  {\"a\":1}  "))
    }

    @Test
    fun parseValidation_strips_fence_and_parses() {
        val result = GeminiParser.parseValidation(VALIDATION_FENCED)
        assertFalse(result.isWarning)
        assertEquals("Looks good.", result.explanation)
        assertTrue(result.suggestions.isEmpty())
    }

    // --- Validation JSON ---

    @Test
    fun validation_object_suggestions() {
        val result = validationObject(VALIDATION_OBJECT_SUGGESTIONS)
        assertTrue(result.isWarning)
        assertTrue(result.explanation.isNotBlank())
        assertEquals(3, result.suggestions.size)
        assertEquals("Polite", result.suggestions[0].tone)
        assertEquals("Could you please help me?", result.suggestions[0].text)
        assertEquals("Direct", result.suggestions[2].tone)
    }

    @Test
    fun validation_string_suggestion_becomes_Alternative_and_blanks_are_dropped() {
        val result = validationObject(VALIDATION_STRING_AND_BLANK_SUGGESTIONS)
        assertFalse(result.isWarning)
        // plain string -> "Alternative"; blank-text object and blank string dropped
        assertEquals(2, result.suggestions.size)
        assertEquals("Alternative", result.suggestions[0].tone)
        assertEquals("Nice to meet you.", result.suggestions[0].text)
        assertEquals("Casual", result.suggestions[1].tone)
        assertEquals("Good to meet you.", result.suggestions[1].text)
    }

    @Test
    fun validation_missing_fields_use_defaults() {
        val result = GeminiParser.parseValidationObject(Json.parseToJsonElement("{}").jsonObject)
        assertFalse(result.isWarning)
        assertEquals("", result.explanation)
        assertTrue(result.suggestions.isEmpty())
    }

    @Test
    fun validation_missing_suggestions_array_yields_empty_list() {
        val result = GeminiParser.parseValidationObject(
            Json.parseToJsonElement("{\"isWarning\":true,\"explanation\":\"x\"}").jsonObject,
        )
        assertTrue(result.isWarning)
        assertEquals("x", result.explanation)
        assertTrue(result.suggestions.isEmpty())
    }

    private companion object {
        const val GOOGLE_SINGLE_SENTENCE =
            "[[[\"Hola\",\"Hello\",null,null,10]],null,\"en\",null,null,null,null,[]]"

        const val GOOGLE_MULTI_SENTENCE =
            "[[[\"Hola. \",\"Hello. \",null,null,3],[\"¿Cómo estás?\",\"How are you?\",null,null,3]," +
                "[\" Adiós.\",\"Goodbye.\",null,null,1]],null,\"en\",null,null,null,null,[]]"

        val VALIDATION_FENCED =
            """
            ```json
            {
              "isWarning": false,
              "explanation": "Looks good.",
              "suggestions": []
            }
            ```
            """.trimIndent()

        val VALIDATION_OBJECT_SUGGESTIONS =
            """
            {
              "isWarning": true,
              "explanation": "The literal translation changes the tone; in the target culture this reads as overly blunt.",
              "suggestions": [
                { "tone": "Polite", "text": "Could you please help me?" },
                { "tone": "Casual", "text": "Can you help?" },
                { "tone": "Direct", "text": "Help me." }
              ]
            }
            """.trimIndent()

        val VALIDATION_STRING_AND_BLANK_SUGGESTIONS =
            """
            {
              "isWarning": false,
              "explanation": "The translation is accurate and idiomatic.",
              "suggestions": [
                "Nice to meet you.",
                { "tone": "Formal", "text": "" },
                { "tone": "Casual", "text": "Good to meet you." },
                ""
              ]
            }
            """.trimIndent()
    }
}
