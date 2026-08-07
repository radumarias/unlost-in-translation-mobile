package com.example.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManualCodeTest {

    @Test
    fun everyValueRoundTrips() {
        for (value in listOf(0L, 1L, 31L, 32L, 1023L, ManualCode.SPACE - 1)) {
            val code = ManualCode.format(value)
            assertEquals(ManualCode.LENGTH, code.length, code)
            assertEquals(value, ManualCode.parseOrNull(code), code)
        }
    }

    @Test
    fun theCodeSpaceIsThirtyBits() {
        // Stated so it cannot drift silently: issue #34 §5's whole argument — the code
        // is not a secret and the SAS is what protects the manual path — rests on it.
        assertEquals(1L shl 30, ManualCode.SPACE)
        assertEquals("000000", ManualCode.format(0))
        assertEquals("ZZZZZZ", ManualCode.format(ManualCode.SPACE - 1))
    }

    @Test
    fun caseSpacesAndDashesAreForgiven() {
        val code = ManualCode.format(123_456)
        val typed = code.lowercase().chunked(3).joinToString("-")
        assertEquals(ManualCode.parseOrNull(code), ManualCode.parseOrNull(typed))
        assertEquals(ManualCode.parseOrNull(code), ManualCode.parseOrNull(" $code "))
    }

    @Test
    fun theLookAlikeLettersAreFoldedOntoWhatTheyResemble() {
        // Crockford's alphabet has no I, L, O or U precisely so that a person reading a
        // code aloud cannot produce an ambiguous one; if they type one anyway, take the
        // obvious meaning rather than an error.
        assertEquals(ManualCode.parseOrNull("1B2C34"), ManualCode.parseOrNull("IB2C34"))
        assertEquals(ManualCode.parseOrNull("1B2C34"), ManualCode.parseOrNull("LB2C34"))
        assertEquals(ManualCode.parseOrNull("0B2C34"), ManualCode.parseOrNull("OB2C34"))
        assertEquals(ManualCode.parseOrNull("VB2C34"), ManualCode.parseOrNull("UB2C34"))
    }

    @Test
    fun generatedCodesUseOnlyTheAlphabet() {
        repeat(200) {
            val code = SessionTicket.newManualCode()
            assertTrue(Regex("^[0-9A-HJKMNP-TV-Z]{6}$").matches(code), code)
        }
    }

    @Test
    fun theWrongLengthIsNotACode() {
        assertNull(ManualCode.parseOrNull("ABC12"))
        assertNull(ManualCode.parseOrNull("ABC1234"))
        assertNull(ManualCode.parseOrNull(""))
    }

    @Test
    fun aCharacterOutsideTheAlphabetIsNotACode() {
        assertNull(ManualCode.parseOrNull("ABC12@"))
        assertNull(ManualCode.parseOrNull("ĂBC123"))
    }

    @Test
    fun normalizeGivesTheFormToShowTheUser() {
        assertEquals("1B2C34", assertNotNull(ManualCode.normalizeOrNull("lb2c 34")))
        assertNull(ManualCode.normalizeOrNull("nope"))
    }
}
