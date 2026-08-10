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

    // --- sanityCheck / idiomExplanation (web-feature port) ---

    @Test
    fun validation_reads_sanity_check_and_idiom_explanation() {
        val result = validationObject(
            """
            {
              "isWarning": false,
              "explanation": "Fine.",
              "sanityCheck": "They will hear a polite request for the bill.",
              "idiomExplanation": "\"Break a leg\" means good luck; a Spanish speaker would say \"mucha mierda\"."
            }
            """.trimIndent(),
        )
        assertEquals("They will hear a polite request for the bill.", result.sanityCheck)
        assertTrue(result.idiomExplanation!!.contains("mucha mierda"))
    }

    @Test
    fun validation_absent_sanity_check_and_idiom_fall_back_to_absent() {
        // A model that ignores the added schema keys must still parse — this is
        // the pre-port response shape.
        val result = validationObject("{\"isWarning\":false,\"explanation\":\"Fine.\"}")
        assertEquals("", result.sanityCheck)
        assertNull(result.idiomExplanation)
    }

    @Test
    fun validation_explicit_null_idiom_means_no_idiom() {
        // The schema tells the model to send null when there is no idiom; that
        // must not surface as the literal string "null" in the UI.
        val result = validationObject("{\"explanation\":\"x\",\"idiomExplanation\":null}")
        assertNull(result.idiomExplanation)
    }

    @Test
    fun validation_blank_idiom_is_treated_as_absent() {
        val result = validationObject("{\"explanation\":\"x\",\"idiomExplanation\":\"   \"}")
        assertNull(result.idiomExplanation)
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

    // --- Auto-detect (web-feature port) ---

    @Test
    fun google_reports_the_detected_source_language() {
        val result = GoogleResponseParser.extractWithDetection(GOOGLE_SINGLE_SENTENCE)
        assertEquals("Hola", result.text)
        assertEquals("en", result.detectedLangCode)
    }

    @Test
    fun google_detection_is_blank_when_the_endpoint_does_not_say() {
        // Shorter body shapes turn up; silence must not be read as a language.
        val result = GoogleResponseParser.extractWithDetection("[[[\"Hola\",\"Hello\"]]]")
        assertEquals("Hola", result.text)
        assertEquals("", result.detectedLangCode)
    }

    @Test
    fun gemini_detection_reads_the_code_and_the_translation() {
        val result = GeminiDetectionParser.parseDetection(
            """{"detectedSourceLanguage":"fr","translation":"Hola"}""",
        )
        assertEquals("fr", result.detectedLangCode)
        assertEquals("Hola", result.text)
    }

    @Test
    fun gemini_detection_without_a_translation_is_a_parse_error() {
        assertFailsWith<SerializationException> {
            GeminiDetectionParser.parseDetection("""{"detectedSourceLanguage":"fr"}""")
        }
    }

    // --- Image OCR payloads (web-feature port) ---

    @Test
    fun vision_reads_the_code_transcription_and_translation() {
        val result = GeminiVisionParser.parseImageTranslation(
            """{"detectedLanguage":"fr","originalText":"Plat du jour","translation":"Dish of the day"}""",
        )
        assertEquals("fr", result.detectedLangCode)
        assertEquals("Plat du jour", result.originalText)
        assertEquals("Dish of the day", result.translation)
    }

    @Test
    fun vision_without_a_translation_is_a_parse_error() {
        // The user pointed a camera at something; an empty card tells them
        // nothing about why.
        assertFailsWith<SerializationException> {
            GeminiVisionParser.parseImageTranslation("""{"detectedLanguage":"fr","originalText":"..."}""")
        }
    }

    @Test
    fun vision_tolerates_a_missing_transcription() {
        val result = GeminiVisionParser.parseImageTranslation("""{"translation":"Dish of the day"}""")
        assertEquals("", result.originalText)
        assertEquals("", result.detectedLangCode)
    }

    // --- Rewrite payloads (web-feature port) ---

    @Test
    fun rewrite_initial_response_carries_direction_and_alternatives() {
        val result = GeminiRewriteParser.parseRewrite(
            """
            {
              "rewriteDirection": "More polite",
              "rewrittenSource": "Could we have the bill, please?",
              "alternativeDirections": ["More formal", "Adapt to local culture"]
            }
            """.trimIndent(),
        )
        assertEquals("More polite", result.rewriteDirection)
        assertEquals("Could we have the bill, please?", result.rewrittenSource)
        assertEquals(listOf("More formal", "Adapt to local culture"), result.alternativeDirections)
    }

    @Test
    fun rewrite_directed_response_may_carry_only_the_text() {
        val result = GeminiRewriteParser.parseRewrite("""{"rewrittenSource":"The bill, please."}""")
        assertEquals("", result.rewriteDirection)
        assertTrue(result.alternativeDirections.isEmpty())
    }

    @Test
    fun rewrite_strips_markdown_fences_like_validation_does() {
        val result = GeminiRewriteParser.parseRewrite("```json\n{\"rewrittenSource\":\"Hi.\"}\n```")
        assertEquals("Hi.", result.rewrittenSource)
    }

    @Test
    fun rewrite_drops_blank_alternative_directions() {
        val result = GeminiRewriteParser.parseRewrite(
            """{"rewrittenSource":"Hi.","alternativeDirections":["Warmer","","   "]}""",
        )
        assertEquals(listOf("Warmer"), result.alternativeDirections)
    }

    @Test
    fun rewrite_without_text_is_a_parse_error() {
        // Nothing to offer the user; an empty card reads as a broken app.
        assertFailsWith<SerializationException> {
            GeminiRewriteParser.parseRewrite("""{"rewriteDirection":"More polite"}""")
        }
    }

    @Test
    fun validation_survives_a_stray_bracket_after_the_object() {
        // Seen in the browser on 2026-08-10 on a perfectly good translation: the
        // object was valid and complete, and one character of trailing punctuation
        // cost the user their cultural check.
        val result = GeminiParser.parseValidation(
            """{"isWarning":false,"explanation":"Reads naturally.","sanityCheck":"Good morning."} ]""",
        )
        assertFalse(result.isWarning)
        assertEquals("Reads naturally.", result.explanation)
        assertEquals("Good morning.", result.sanityCheck)
    }

    @Test
    fun validation_survives_prose_around_the_object() {
        val result = GeminiParser.parseValidation(
            """Here is the JSON you asked for: {"isWarning":true,"explanation":"Too blunt."} Hope that helps!""",
        )
        assertTrue(result.isWarning)
        assertEquals("Too blunt.", result.explanation)
    }

    @Test
    fun carving_the_object_out_is_string_aware() {
        // A brace inside the explanation must not end the object early — the
        // explanation is free text, and a model quoting code is not far-fetched.
        val result = GeminiParser.parseValidation(
            """{"isWarning":false,"explanation":"Use {0} for the name \"here\"."} trailing""",
        )
        assertEquals("""Use {0} for the name "here".""", result.explanation)
    }

    @Test
    fun a_clean_response_still_parses_without_carving() {
        assertNull(GeminiParser.firstJsonObject("no object here"))
        val result = GeminiParser.parseValidation(VALIDATION_FENCED)
        assertTrue(result.explanation.isNotBlank())
    }

    @Test
    fun text_with_no_object_at_all_is_still_a_parse_error() {
        // Carving is a second chance, not a licence to invent a result.
        assertFailsWith<SerializationException> {
            GeminiParser.parseValidation("the model apologised and said nothing else")
        }
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
