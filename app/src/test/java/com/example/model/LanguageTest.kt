package com.example.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks `Language.getByCode` lookup + fallback and the language-tag values used
 * at the TTS / speech-recognition boundary (Phase 1 tasks 2-3). Pure Kotlin, no
 * Android dependency.
 */
class LanguageTest {

    @Test
    fun `getByCode returns the matching language`() {
        val ro = Language.getByCode("ro")
        assertEquals("Romanian", ro.name)
        assertEquals("ro-RO", ro.languageTag)
    }

    @Test
    fun `getByCode falls back to English for unknown code`() {
        val fallback = Language.getByCode("xx")
        assertEquals("en", fallback.code)
        assertEquals("en-US", fallback.languageTag)
    }

    @Test
    fun `all supported languages carry a BCP47 language tag`() {
        assertEquals(16, Language.supportedLanguages.size)
        Language.supportedLanguages.forEach { lang ->
            assertTrue("${lang.code} tag missing region", lang.languageTag.contains("-"))
        }
    }
}
