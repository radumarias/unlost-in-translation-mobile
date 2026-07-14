package com.example.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks `Language.getByCode` lookup + fallback and the language-tag values used
 * at the TTS / speech-recognition boundary (Phase 1 tasks 2-3). Pure Kotlin, no
 * Android dependency — lives in commonTest.
 */
class LanguageTest {

    @Test
    fun getByCode_returns_the_matching_language() {
        val ro = Language.getByCode("ro")
        assertEquals("Romanian", ro.name)
        assertEquals("ro-RO", ro.languageTag)
    }

    @Test
    fun getByCode_falls_back_to_English_for_unknown_code() {
        val fallback = Language.getByCode("xx")
        assertEquals("en", fallback.code)
        assertEquals("en-US", fallback.languageTag)
    }

    @Test
    fun all_supported_languages_carry_a_BCP47_language_tag() {
        assertEquals(16, Language.supportedLanguages.size)
        Language.supportedLanguages.forEach { lang ->
            assertTrue(lang.languageTag.contains("-"), "${lang.code} tag missing region")
        }
    }
}
