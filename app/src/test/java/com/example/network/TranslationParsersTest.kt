package com.example.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks the observed behavior of the org.json parsing extracted from
 * TranslationService / TranslateViewModel (Phase 1 task 3). Fixtures are real
 * response shapes captured under app/src/test/resources/fixtures. The Ktor +
 * kotlinx.serialization port (task 4) must keep every one of these assertions
 * green. Runs under Robolectric so the real org.json (not the SDK stub) is used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranslationParsersTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().use { it.readText() }

    // --- Google translate_a/single ---

    @Test
    fun `google single sentence`() {
        val out = TranslationParsers.parseGoogleTranslation(fixture("google_single_sentence.json"))
        assertEquals("Hola", out)
    }

    @Test
    fun `google multi sentence concatenates all groups in order`() {
        val out = TranslationParsers.parseGoogleTranslation(fixture("google_multi_sentence.json"))
        assertEquals("Hola. ¿Cómo estás? Adiós.", out)
    }

    @Test
    fun `google empty first group yields empty string`() {
        val out = TranslationParsers.parseGoogleTranslation("[[],null,\"en\"]")
        assertEquals("", out)
    }

    @Test
    fun `google skips zero-length parts`() {
        // second group is an empty inner array and must be skipped, not crash
        val out = TranslationParsers.parseGoogleTranslation("[[[\"A\",\"a\"],[]],null,\"en\"]")
        assertEquals("A", out)
    }

    // --- Gemini fence stripping ---

    @Test
    fun `strip json fence`() {
        val inner = TranslationParsers.stripJsonFences(fixture("validation_fenced.txt"))
        assertTrue(inner.startsWith("{"))
        assertTrue(inner.endsWith("}"))
        // parses cleanly after stripping
        val json = JSONObject(inner)
        assertEquals("Looks good.", json.optString("explanation"))
    }

    @Test
    fun `strip bare fence without json tag`() {
        val inner = TranslationParsers.stripJsonFences("```\n{\"a\":1}\n```")
        assertEquals("{\"a\":1}", inner)
    }

    @Test
    fun `strip leaves unfenced text unchanged apart from trim`() {
        val inner = TranslationParsers.stripJsonFences("  {\"a\":1}  ")
        assertEquals("{\"a\":1}", inner)
    }

    // --- Validation JSON ---

    @Test
    fun `validation object suggestions`() {
        val parsed = TranslationParsers.parseValidation(JSONObject(fixture("validation_object_suggestions.json")))
        assertTrue(parsed.isWarning)
        assertTrue(parsed.explanation.isNotBlank())
        assertEquals(3, parsed.suggestions.size)
        assertEquals("Polite", parsed.suggestions[0].tone)
        assertEquals("Could you please help me?", parsed.suggestions[0].text)
        assertEquals("Direct", parsed.suggestions[2].tone)
    }

    @Test
    fun `validation string suggestion becomes Alternative and blanks are dropped`() {
        val parsed = TranslationParsers.parseValidation(JSONObject(fixture("validation_string_and_blank_suggestions.json")))
        assertFalse(parsed.isWarning)
        // plain string -> "Alternative"; blank-text object and blank string dropped
        assertEquals(2, parsed.suggestions.size)
        assertEquals("Alternative", parsed.suggestions[0].tone)
        assertEquals("Nice to meet you.", parsed.suggestions[0].text)
        assertEquals("Casual", parsed.suggestions[1].tone)
        assertEquals("Good to meet you.", parsed.suggestions[1].text)
    }

    @Test
    fun `validation missing fields use defaults`() {
        val parsed = TranslationParsers.parseValidation(JSONObject("{}"))
        assertFalse(parsed.isWarning)
        assertEquals("", parsed.explanation)
        assertTrue(parsed.suggestions.isEmpty())
    }

    @Test
    fun `validation missing suggestions array yields empty list`() {
        val parsed = TranslationParsers.parseValidation(
            JSONObject("{\"isWarning\":true,\"explanation\":\"x\"}")
        )
        assertTrue(parsed.isWarning)
        assertEquals("x", parsed.explanation)
        assertTrue(parsed.suggestions.isEmpty())
    }
}
