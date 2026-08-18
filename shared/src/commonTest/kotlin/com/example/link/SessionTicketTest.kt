package com.example.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTicketTest {

    private val sid = SessionTicket.newSessionId()
    private val qr = Base64Url.encode(ByteArray(SessionTicket.QR_SECRET_BYTES) { it.toByte() })
    private val pub = Base64Url.encode(
        ByteArray(SessionTicket.PUBLIC_KEY_BYTES) { if (it == 0) 0x04 else it.toByte() }
    )

    // --- generation ---

    @Test
    fun aGeneratedSessionIdIs128BitsOfBase64Url() {
        val id = SessionTicket.newSessionId()
        assertEquals(SessionTicket.SESSION_ID_LENGTH, id.length)
        assertTrue(Base64Url.isEncodingOf(id, SessionTicket.SESSION_ID_BYTES))
        // The relay validates exactly this at the edge, before allocating anything.
        assertTrue(Regex("^[A-Za-z0-9_-]{22}$").matches(id))
    }

    @Test
    fun sessionIdsAreNotRepeated() {
        val ids = List(200) { SessionTicket.newSessionId() }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun memberIdsFitWhatTheRelayAccepts() {
        val member = SessionTicket.newMemberId()
        assertTrue(Regex("^[A-Za-z0-9_-]{8,64}$").matches(member), member)
        assertNotEquals(member, SessionTicket.newMemberId())
    }

    // --- the URL form ---

    @Test
    fun theUrlFormRoundTrips() {
        val ticket = SessionTicket(sessionId = sid, hostLang = "ro", qrSecret = qr, hostPublicKey = pub)
        val parsed = SessionTicket.parse(ticket.encodeToUrl("https://uitr.example"))
        assertEquals(ticket, parsed)
    }

    @Test
    fun everythingIsInTheFragment() {
        val url = SessionTicket(sessionId = sid, hostLang = "ro", qrSecret = qr).encodeToUrl("https://uitr.example")
        val beforeFragment = url.substringBefore('#')
        // The one rule this design cannot bend: the secret must never be in anything a
        // browser would send to a server.
        assertFalse(qr in beforeFragment)
        assertFalse(sid in beforeFragment)
        assertEquals("https://uitr.example/j", beforeFragment)
    }

    @Test
    fun aSecretInTheQueryStringIsNotHonoured() {
        // Not a payload we produce. Accepting it would legitimise the one shape the
        // design forbids, so a URL with no fragment simply is not a ticket.
        assertNull(SessionTicket.parse("https://uitr.example/j?s=$sid&k=$qr&v=1"))
    }

    @Test
    fun aUrlWithoutASessionIdIsRejected() {
        assertNull(SessionTicket.parse("https://uitr.example/j#l=ro&v=1"))
    }

    // --- the compact form ---

    @Test
    fun theQrCarriesALinkWhenThereIsAWebBuildToPointAtAndTheCompactFormOtherwise() {
        val ticket = SessionTicket(sessionId = sid, hostLang = "ro", qrSecret = qr)

        assertEquals(ticket.encodeToUrl("https://uitr.example"), ticket.encodeForQr("https://uitr.example"))
        assertEquals(ticket.encodeCompact(), ticket.encodeForQr(""))
        // Blank, not just empty: an unset WEB_BASE_URL read through a .env can arrive
        // as whitespace, and a QR pointing at "https:///j#…" scans as a broken link.
        assertEquals(ticket.encodeCompact(), ticket.encodeForQr("   "))
    }

    @Test
    fun bothQrPayloadsParseBackToTheSameTicket() {
        // The two forms are what a scanner and a stock camera app respectively hand
        // over; a guest joined either way must land in the same session.
        val ticket = SessionTicket(sessionId = sid, hostLang = "ro", qrSecret = qr)

        assertEquals(
            SessionTicket.parse(ticket.encodeForQr("https://uitr.example")),
            SessionTicket.parse(ticket.encodeForQr("")),
        )
    }

    @Test
    fun theCompactFormRoundTrips() {
        val ticket = SessionTicket(sessionId = sid, hostLang = "en", qrSecret = qr, hostPublicKey = pub)
        assertEquals(ticket, SessionTicket.parse(ticket.encodeCompact()))
    }

    @Test
    fun theCompactFormRoundTripsWithoutTheOptionalFields() {
        val ticket = SessionTicket(sessionId = sid, hostLang = "en")
        val parsed = assertNotNull(SessionTicket.parse(ticket.encodeCompact()))
        assertEquals(ticket, parsed)
        assertNull(parsed.qrSecret)
        assertNull(parsed.hostPublicKey)
    }

    @Test
    fun aCompactPayloadWithTheWrongNumberOfPartsIsRejected() {
        assertNull(SessionTicket.parse("uitr:v1:$sid"))
        assertNull(SessionTicket.parse("uitr:v1:$sid:::en:extra"))
    }

    // --- validation ---

    @Test
    fun aSessionIdOfTheWrongLengthIsRejected() {
        assertNull(SessionTicket.parse("uitr:v1:tooshort:::en"))
        assertNull(SessionTicket.parse("uitr:v1:${sid}extra:::en"))
    }

    @Test
    fun aSessionIdThatIsNotBase64UrlIsRejected() {
        assertNull(SessionTicket.parse("uitr:v1:aaaaaaaaaaaaaaaaaaaa!!:::en"))
    }

    @Test
    fun aQrSecretOfTheWrongSizeIsRejected() {
        val short = Base64Url.encode(ByteArray(16))
        assertNull(SessionTicket.parse("uitr:v1:$sid:$short::en"))
    }

    @Test
    fun aCompressedOrMalformedPublicKeyIsRejected() {
        // #34 F3: WebCrypto's importKey("raw") takes the 65-byte uncompressed point and
        // nothing else, so a compressed key would only fail later, on the other phone.
        val compressed = Base64Url.encode(ByteArray(33) { if (it == 0) 0x02 else 1 })
        assertNull(SessionTicket.parse("uitr:v1:$sid::$compressed:en"))
        val wrongPrefix = Base64Url.encode(ByteArray(SessionTicket.PUBLIC_KEY_BYTES) { 1 })
        assertNull(SessionTicket.parse("uitr:v1:$sid::$wrongPrefix:en"))
    }

    @Test
    fun anUnknownProtocolVersionIsRejected() {
        assertNull(SessionTicket.parse("uitr:v2:$sid:::en"))
        assertNull(SessionTicket.parse("https://uitr.example/j#s=$sid&v=9"))
    }

    @Test
    fun aLanguageCodeThisBuildDoesNotKnowStillParses() {
        // Shape only. Whether the code names a supported language is the view model's
        // question; failing the parse would turn an older peer into an unreadable QR.
        val parsed = assertNotNull(SessionTicket.parse("uitr:v1:$sid:::sw"))
        assertEquals("sw", parsed.hostLang)
    }

    @Test
    fun emptyAndBlankInputIsNotATicket() {
        assertNull(SessionTicket.parse(""))
        assertNull(SessionTicket.parse("   "))
        assertNull(SessionTicket.parse("https://example.com/"))
    }

    // --- the manual code ---

    @Test
    fun aManualCodeResolvesToTheSameSessionOnBothPhones() {
        val code = SessionTicket.newManualCode()
        val host = assertNotNull(SessionTicket.forManualCode(code))
        val guest = assertNotNull(SessionTicket.parse(code.lowercase()))
        assertEquals(host.sessionId, guest.sessionId)
        assertTrue(guest.isManual)
    }

    @Test
    fun aManualSessionIdIsStillWhatTheRelayAccepts() {
        val ticket = assertNotNull(SessionTicket.forManualCode(SessionTicket.newManualCode()))
        assertTrue(Regex("^[A-Za-z0-9_-]{22}$").matches(ticket.sessionId), ticket.sessionId)
    }

    @Test
    fun differentCodesAreDifferentSessions() {
        val a = assertNotNull(SessionTicket.forManualCode("ABC123"))
        val b = assertNotNull(SessionTicket.forManualCode("ABC124"))
        assertNotEquals(a.sessionId, b.sessionId)
    }

    @Test
    fun somethingThatIsNotACodeIsNotATicket() {
        assertNull(SessionTicket.parse("hello"))
        assertNull(SessionTicket.parse("ABCDEFG"))
    }

    // --- the secret never leaks ---

    @Test
    fun toStringRedactsTheSecretAndTheKey() {
        val ticket = SessionTicket(sessionId = sid, hostLang = "ro", qrSecret = qr, hostPublicKey = pub)
        val text = ticket.toString()
        assertFalse(qr in text, "the QR secret must never reach a log line")
        assertFalse(pub in text)
        assertTrue("<REDACTED>" in text)
        // The session id is not a secret in the same sense — it is in the URL the relay
        // itself sees — and having it in a log is what makes a report actionable.
        assertTrue(sid in text)
    }

    @Test
    fun theRelayUrlNeverCarriesTheSecret() {
        // A belt-and-braces check on the shape the link builds; the link owns the URL,
        // but this is the invariant, so it is asserted where the rule is written down.
        val ticket = SessionTicket(sessionId = sid, qrSecret = qr)
        val relayUrl = "wss://relay.example/s/${ticket.sessionId}?m=member-1234"
        assertFalse(qr in relayUrl)
    }
}
