package com.example.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the observed behavior of the parsing extracted from the old
 * TranslationService / TranslateViewModel, now re-implemented on
 * kotlinx.serialization (Phase 1 task 4). Fixtures are the same real response
 * shapes captured in task 3 under app/src/test/resources/fixtures — the port
 * must keep every assertion green. Pure JVM (no Robolectric / org.json).
 */
class NetworkParsersTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().use { it.readText() }

    private fun validationObject(name: String) =
        GeminiParser.parseValidationObject(Json.parseToJsonElement(fixture(name)).jsonObject)

    // --- Google translate_a/single ---

    @Test
    fun `google single sentence`() {
        assertEquals("Hola", GoogleResponseParser.extract(fixture("google_single_sentence.json")))
    }

    @Test
    fun `google multi sentence concatenates all groups in order`() {
        assertEquals(
            "Hola. ¿Cómo estás? Adiós.",
            GoogleResponseParser.extract(fixture("google_multi_sentence.json")),
        )
    }

    @Test
    fun `google empty first group yields empty string`() {
        assertEquals("", GoogleResponseParser.extract("[[],null,\"en\"]"))
    }

    @Test
    fun `google skips zero-length parts`() {
        // second group is an empty inner array and must be skipped, not crash
        assertEquals("A", GoogleResponseParser.extract("[[[\"A\",\"a\"],[]],null,\"en\"]"))
    }

    // --- Gemini fence stripping ---

    @Test
    fun `strip json fence`() {
        val inner = GeminiParser.stripJsonFences(fixture("validation_fenced.txt"))
        assertTrue(inner.startsWith("{"))
        assertTrue(inner.endsWith("}"))
    }

    @Test
    fun `strip bare fence without json tag`() {
        assertEquals("{\"a\":1}", GeminiParser.stripJsonFences("```\n{\"a\":1}\n```"))
    }

    @Test
    fun `strip leaves unfenced text unchanged apart from trim`() {
        assertEquals("{\"a\":1}", GeminiParser.stripJsonFences("  {\"a\":1}  "))
    }

    @Test
    fun `parseValidation strips fence and parses`() {
        val result = GeminiParser.parseValidation(fixture("validation_fenced.txt"))
        assertFalse(result.isWarning)
        assertEquals("Looks good.", result.explanation)
        assertTrue(result.suggestions.isEmpty())
    }

    // --- Validation JSON ---

    @Test
    fun `validation object suggestions`() {
        val result = validationObject("validation_object_suggestions.json")
        assertTrue(result.isWarning)
        assertTrue(result.explanation.isNotBlank())
        assertEquals(3, result.suggestions.size)
        assertEquals("Polite", result.suggestions[0].tone)
        assertEquals("Could you please help me?", result.suggestions[0].text)
        assertEquals("Direct", result.suggestions[2].tone)
    }

    @Test
    fun `validation string suggestion becomes Alternative and blanks are dropped`() {
        val result = validationObject("validation_string_and_blank_suggestions.json")
        assertFalse(result.isWarning)
        // plain string -> "Alternative"; blank-text object and blank string dropped
        assertEquals(2, result.suggestions.size)
        assertEquals("Alternative", result.suggestions[0].tone)
        assertEquals("Nice to meet you.", result.suggestions[0].text)
        assertEquals("Casual", result.suggestions[1].tone)
        assertEquals("Good to meet you.", result.suggestions[1].text)
    }

    @Test
    fun `validation missing fields use defaults`() {
        val result = GeminiParser.parseValidationObject(Json.parseToJsonElement("{}").jsonObject)
        assertFalse(result.isWarning)
        assertEquals("", result.explanation)
        assertTrue(result.suggestions.isEmpty())
    }

    @Test
    fun `validation missing suggestions array yields empty list`() {
        val result = GeminiParser.parseValidationObject(
            Json.parseToJsonElement("{\"isWarning\":true,\"explanation\":\"x\"}").jsonObject,
        )
        assertTrue(result.isWarning)
        assertEquals("x", result.explanation)
        assertTrue(result.suggestions.isEmpty())
    }
}
