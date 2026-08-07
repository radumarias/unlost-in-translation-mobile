package com.example.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkMessageTest {

    @Test
    fun helloRoundTrips() {
        val message = LinkMessage.hello(from = "dev-a", lang = "ro", name = "Radu", ts = 1_700_000_000_000)
        assertEquals(message, LinkCodec.decode(LinkCodec.encode(message)))
    }

    @Test
    fun turnRoundTrips() {
        val message = LinkMessage.turn(
            from = "dev-a",
            id = "t-1",
            lang = "ro",
            original = "Bună ziua",
            translated = "Good afternoon",
            ts = 1_700_000_000_000,
        )
        assertEquals(message, LinkCodec.decode(LinkCodec.encode(message)))
    }

    @Test
    fun byeRoundTrips() {
        val message = LinkMessage.bye(from = "dev-a")
        assertEquals(message, LinkCodec.decode(LinkCodec.encode(message)))
    }

    @Test
    fun onlyTheFieldsThatAreSetAreEncoded() {
        val encoded = LinkCodec.encode(LinkMessage.bye(from = "dev-a"))
        // A `bye` is three fields. On a relay that meters by the frame, spending bytes
        // to say a field is missing is waste, and null-vs-absent is a real distinction
        // once #34 starts reading `n` and `ct`.
        assertEquals("""{"v":1,"type":"bye","from":"dev-a"}""", encoded)
    }

    @Test
    fun theIssue34SlotsSurviveTheRoundTripUntouched() {
        // #33 does not use these, but the wire type must not change when #34 lands:
        // two phones can be on different app versions.
        val message = LinkMessage(
            type = LinkMessage.TYPE_BYE,
            from = "dev-a",
            pub = "BFxu",
            mac = "q1w2",
            n = 42,
            ct = "ZGVhZGJlZWY",
        )
        val decoded = LinkCodec.decode(LinkCodec.encode(message))
        assertEquals(message, decoded)
    }

    @Test
    fun anUnknownFieldFromANewerPeerIsIgnoredRatherThanFatal() {
        val decoded = LinkCodec.decode(
            """{"v":1,"type":"bye","from":"dev-a","somethingNewInV2":"x"}"""
        )
        assertEquals(LinkMessage.bye("dev-a"), decoded)
    }

    @Test
    fun aTurnMissingItsIdIsRejected() {
        // Without an id there is no de-duplication, so a replayed frame after a
        // reconnect would show the same sentence twice.
        assertNull(
            LinkCodec.decode(
                """{"v":1,"type":"turn","from":"dev-a","lang":"ro","original":"a","translated":"b"}"""
            )
        )
    }

    @Test
    fun aTurnMissingItsTranslationIsRejected() {
        assertNull(
            LinkCodec.decode(
                """{"v":1,"type":"turn","from":"dev-a","lang":"ro","id":"t-1","original":"a"}"""
            )
        )
    }

    @Test
    fun aHelloWithoutALanguageIsRejected() {
        assertNull(LinkCodec.decode("""{"v":1,"type":"hello","from":"dev-a"}"""))
    }

    @Test
    fun aFrameWithoutFromIsRejected() {
        assertNull(LinkCodec.decode("""{"v":1,"type":"bye"}"""))
    }

    @Test
    fun aFrameFromAnotherProtocolVersionIsRejected() {
        assertNull(LinkCodec.decode("""{"v":2,"type":"bye","from":"dev-a"}"""))
    }

    @Test
    fun theEncryptedFrameTypesRoundTrip() {
        // Issue #34's three: `kex` and `cfm` are the handshake and are the only
        // frames ever sent in the clear; `enc` is the sealed envelope everything
        // else — `hello` included — travels inside.
        assertEquals(
            LinkMessage.kex("dev-a", "BFxu"),
            LinkCodec.decode(LinkCodec.encode(LinkMessage.kex("dev-a", "BFxu"))),
        )
        assertEquals(
            LinkMessage.cfm("dev-a", "q1w2"),
            LinkCodec.decode(LinkCodec.encode(LinkMessage.cfm("dev-a", "q1w2"))),
        )
        assertEquals(
            LinkMessage.enc("dev-a", 7, "ZGVhZGJlZWY"),
            LinkCodec.decode(LinkCodec.encode(LinkMessage.enc("dev-a", 7, "ZGVhZGJlZWY"))),
        )
    }

    @Test
    fun anEncryptedFrameMissingItsPartsIsRejected() {
        // A `kex` with no key, a `cfm` with no mac and an `enc` with no counter are
        // all frames nobody can act on, and the handshake fails closed on them.
        assertNull(LinkCodec.decode("""{"v":1,"type":"kex","from":"dev-a"}"""))
        assertNull(LinkCodec.decode("""{"v":1,"type":"cfm","from":"dev-a"}"""))
        assertNull(LinkCodec.decode("""{"v":1,"type":"enc","from":"dev-a","ct":"AAAA"}"""))
    }

    @Test
    fun anUnknownFrameTypeIsStillRejected() {
        assertNull(LinkCodec.decode("""{"v":1,"type":"whatever","from":"dev-a"}"""))
    }

    @Test
    fun garbageIsNullRatherThanAnException() {
        assertNull(LinkCodec.decode("not json at all"))
        assertNull(LinkCodec.decode(""))
        assertNull(LinkCodec.decode("[]"))
    }

    @Test
    fun textWithNewlinesAndQuotesSurvivesTheWire() {
        // Speech recognition produces all of this, and the frame is one line of JSON.
        val awkward = "He said \"hello\"\nand left\ttoo\\soon — 你好"
        val message = LinkMessage.turn("dev-a", "t-1", "en", awkward, awkward)
        val encoded = LinkCodec.encode(message)
        assertTrue('\n' !in encoded, "a frame must stay on one line")
        assertEquals(message, LinkCodec.decode(encoded))
    }
}
