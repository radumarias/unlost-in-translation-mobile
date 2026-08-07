package com.example.link.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The record layer and the confirmation exchange (issue #34 §3.5, §3.6).
 *
 * In `commonTest`, so every one of these runs twice: on the JVM against the JDK
 * provider and in ChromeHeadless against WebCrypto. The second run is the one that
 * matters — two phones in a session may not be the same platform, and a schedule
 * that derived differently in a browser would be a bug nobody could reproduce on a
 * laptop.
 */
class SessionCryptoTest {

    private val sessionId = ByteArray(16) { it.toByte() }
    private val qrSecret = ByteArray(32) { (it * 3).toByte() }

    private suspend fun pair(): Pair<SessionCrypto, SessionCrypto> {
        val host = SessionKeyExchange.generate()
        val guest = SessionKeyExchange.generate()
        val hostSchedule = SessionKeySchedule.derive(
            qrSecret = qrSecret,
            sharedSecret = host.agree(guest.publicKey),
            sessionId = sessionId,
            hostPublicKey = host.publicKey,
            guestPublicKey = guest.publicKey,
        )
        val guestSchedule = SessionKeySchedule.derive(
            qrSecret = qrSecret,
            sharedSecret = guest.agree(host.publicKey),
            sessionId = sessionId,
            hostPublicKey = host.publicKey,
            guestPublicKey = guest.publicKey,
        )
        return SessionCrypto(hostSchedule, LinkRole.HOST) to SessionCrypto(guestSchedule, LinkRole.GUEST)
    }

    // --- the handshake ---

    @Test
    fun bothSidesDeriveTheSameSchedule() = runTest {
        val host = SessionKeyExchange.generate()
        val guest = SessionKeyExchange.generate()

        val a = SessionKeySchedule.derive(
            qrSecret, host.agree(guest.publicKey), sessionId, host.publicKey, guest.publicKey
        )
        val b = SessionKeySchedule.derive(
            qrSecret, guest.agree(host.publicKey), sessionId, host.publicKey, guest.publicKey
        )

        assertContentEquals(a.hostToGuest, b.hostToGuest)
        assertContentEquals(a.guestToHost, b.guestToHost)
        assertContentEquals(a.confirmation, b.confirmation)
        assertEquals(a.sas, b.sas)
    }

    @Test
    fun theTwoDirectionsGetDifferentKeys() = runTest {
        val host = SessionKeyExchange.generate()
        val guest = SessionKeyExchange.generate()
        val schedule = SessionKeySchedule.derive(
            qrSecret, host.agree(guest.publicKey), sessionId, host.publicKey, guest.publicKey
        )
        // If these ever collided the two phones would share a nonce space, which is
        // the one mistake that breaks GCM outright.
        assertTrue(!schedule.hostToGuest.contentEquals(schedule.guestToHost))
        assertTrue(!schedule.hostToGuest.contentEquals(schedule.confirmation))
    }

    @Test
    fun confirmationSucceedsBetweenGenuinePeers() = runTest {
        val (host, guest) = pair()
        host.verifyPeerConfirmation(guest.confirmation())
        guest.verifyPeerConfirmation(host.confirmation())
    }

    @Test
    fun aSubstitutedGuestKeyIsCaughtByConfirmation() = runTest {
        // The MITM test. The QR only flows host → guest, so the guest authenticates
        // the host for free; the host received `guestPub` over the relay and cannot.
        // A relay that swaps in its own key derives a different schedule, and this
        // is where that shows up.
        val host = SessionKeyExchange.generate()
        val guest = SessionKeyExchange.generate()
        val attacker = SessionKeyExchange.generate()

        // The host believes it is talking to the attacker's key...
        val hostSchedule = SessionKeySchedule.derive(
            qrSecret, host.agree(attacker.publicKey), sessionId, host.publicKey, attacker.publicKey
        )
        // ...while the real guest derives against its own.
        val guestSchedule = SessionKeySchedule.derive(
            qrSecret, guest.agree(host.publicKey), sessionId, host.publicKey, guest.publicKey
        )

        val hostSide = SessionCrypto(hostSchedule, LinkRole.HOST)
        val guestSide = SessionCrypto(guestSchedule, LinkRole.GUEST)

        val failure = assertFailsWith<LinkCryptoException> {
            hostSide.verifyPeerConfirmation(guestSide.confirmation())
        }
        assertEquals(LinkCryptoException.Reason.CONFIRMATION_FAILED, failure.reason)
    }

    @Test
    fun anAttackerWithoutTheQrSecretDerivesNothingUseful() = runTest {
        // A relay sees every byte on the wire but never the QR secret. Even with a
        // perfect view of both public keys it cannot reach the same schedule.
        val host = SessionKeyExchange.generate()
        val guest = SessionKeyExchange.generate()
        val dh = host.agree(guest.publicKey)

        val real = SessionKeySchedule.derive(qrSecret, dh, sessionId, host.publicKey, guest.publicKey)
        val guessed = SessionKeySchedule.derive(
            ByteArray(32), dh, sessionId, host.publicKey, guest.publicKey
        )
        assertTrue(!real.hostToGuest.contentEquals(guessed.hostToGuest))
        assertTrue(!real.confirmation.contentEquals(guessed.confirmation))
    }

    @Test
    fun aMalformedPublicKeyIsRejectedBeforeItReachesTheProvider() = runTest {
        val host = SessionKeyExchange.generate()

        // Compressed: 33 bytes with an 0x02 tag. Valid SEC1, and exactly what #34 F3
        // says browsers will not reliably import — so it fails here, on the frame
        // that carried it, rather than on the other person's phone.
        val compressed = ByteArray(33) { if (it == 0) 0x02 else 1 }
        assertEquals(
            LinkCryptoException.Reason.BAD_PUBLIC_KEY,
            assertFailsWith<LinkCryptoException> { host.agree(compressed) }.reason,
        )
        assertEquals(
            LinkCryptoException.Reason.BAD_PUBLIC_KEY,
            assertFailsWith<LinkCryptoException> { host.agree(ByteArray(65)) }.reason,
        )
    }

    @Test
    fun aPointThatIsNotOnTheCurveIsRejected() = runTest {
        val host = SessionKeyExchange.generate()
        // Right shape, wrong point: 0x04 with coordinates that do not satisfy the
        // curve equation. Only the provider can tell, and it does.
        val offCurve = ByteArray(65) { if (it == 0) 0x04 else 0x01 }
        assertEquals(
            LinkCryptoException.Reason.BAD_PUBLIC_KEY,
            assertFailsWith<LinkCryptoException> { host.agree(offCurve) }.reason,
        )
    }

    // --- the record layer ---

    @Test
    fun aRecordRoundTripsBetweenTheTwoSides() = runTest {
        val (host, guest) = pair()
        val frame = """{"v":1,"type":"turn","from":"h","id":"t-1"}""".encodeToByteArray()

        val sealed = host.seal(frame, from = "host-1")
        assertEquals(0L, sealed.counter)
        assertContentEquals(frame, guest.open(sealed.ciphertext, sealed.counter, from = "host-1"))
    }

    @Test
    fun bothDirectionsWorkAndCountIndependently() = runTest {
        val (host, guest) = pair()

        val h1 = host.seal("one".encodeToByteArray(), "host-1")
        val g1 = guest.seal("two".encodeToByteArray(), "guest-1")
        val h2 = host.seal("three".encodeToByteArray(), "host-1")

        assertEquals(0L, h1.counter)
        assertEquals(0L, g1.counter, "each direction counts from zero on its own key")
        assertEquals(1L, h2.counter)

        assertContentEquals("one".encodeToByteArray(), guest.open(h1.ciphertext, h1.counter, "host-1"))
        assertContentEquals("two".encodeToByteArray(), host.open(g1.ciphertext, g1.counter, "guest-1"))
        assertContentEquals("three".encodeToByteArray(), guest.open(h2.ciphertext, h2.counter, "host-1"))
    }

    @Test
    fun aTamperedByteFailsTheTag() = runTest {
        val (host, guest) = pair()
        val sealed = host.seal("hello".encodeToByteArray(), "host-1")
        val tampered = sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

        assertEquals(
            LinkCryptoException.Reason.DECRYPTION_FAILED,
            assertFailsWith<LinkCryptoException> { guest.open(tampered, sealed.counter, "host-1") }.reason,
        )
    }

    @Test
    fun rewritingTheSenderIdBreaksTheTag() = runTest {
        // The header is authenticated even though it is readable, so a relay cannot
        // reattribute a frame it cannot decrypt.
        val (host, guest) = pair()
        val sealed = host.seal("hello".encodeToByteArray(), "host-1")

        assertFailsWith<LinkCryptoException> {
            guest.open(sealed.ciphertext, sealed.counter, from = "somebody-else")
        }
    }

    @Test
    fun rewritingTheCounterBreaksTheTag() = runTest {
        val (host, guest) = pair()
        host.seal("first".encodeToByteArray(), "host-1")
        val sealed = host.seal("second".encodeToByteArray(), "host-1")

        assertFailsWith<LinkCryptoException> {
            guest.open(sealed.ciphertext, counter = 5, from = "host-1")
        }
    }

    @Test
    fun aReplayedRecordIsRejected() = runTest {
        val (host, guest) = pair()
        val sealed = host.seal("hello".encodeToByteArray(), "host-1")
        guest.open(sealed.ciphertext, sealed.counter, "host-1")

        // This is also how the relay's reconnect replay ring is absorbed: the frames
        // it hands back are all at or below `lastSeen`, so they drop silently.
        assertEquals(
            LinkCryptoException.Reason.REPLAY,
            assertFailsWith<LinkCryptoException> {
                guest.open(sealed.ciphertext, sealed.counter, "host-1")
            }.reason,
        )
    }

    @Test
    fun aGenuinelyMissedRecordIsStillAccepted() = runTest {
        val (host, guest) = pair()
        val first = host.seal("one".encodeToByteArray(), "host-1")
        host.seal("two".encodeToByteArray(), "host-1")   // lost in the blip
        val third = host.seal("three".encodeToByteArray(), "host-1")

        guest.open(first.ciphertext, first.counter, "host-1")
        // Gaps are fine — GCM needs no contiguity, and a dropped frame must not
        // wedge the session.
        assertContentEquals(
            "three".encodeToByteArray(),
            guest.open(third.ciphertext, third.counter, "host-1"),
        )
    }

    @Test
    fun aFailedRecordDoesNotAdvanceTheReplayWindow() = runTest {
        // Otherwise anyone able to inject one frame with a high counter could lock
        // the real peer out for the rest of the session.
        val (host, guest) = pair()
        val real = host.seal("hello".encodeToByteArray(), "host-1")
        val forged = ByteArray(real.ciphertext.size) { 0x42 }

        assertFailsWith<LinkCryptoException> { guest.open(forged, counter = 99, from = "host-1") }
        assertContentEquals(
            "hello".encodeToByteArray(),
            guest.open(real.ciphertext, real.counter, "host-1"),
        )
    }

    // --- the nonce, which is the thing that must never repeat ---

    @Test
    fun theNonceIsDirectionAndCounterAndNothingElse() = runTest {
        val nonce = SessionCrypto.nonce(LinkDirection.GUEST_TO_HOST, 1)
        assertEquals(SessionCrypto.NONCE_BYTES, nonce.size)
        assertContentEquals(
            byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            nonce,
        )
        assertContentEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            SessionCrypto.nonce(LinkDirection.HOST_TO_GUEST, 0),
        )
    }

    @Test
    fun theTwoDirectionsNeverShareANonce() = runTest {
        val host = (0L..64L).map { SessionCrypto.nonce(LinkDirection.HOST_TO_GUEST, it).toList() }
        val guest = (0L..64L).map { SessionCrypto.nonce(LinkDirection.GUEST_TO_HOST, it).toList() }
        assertEquals(emptyList(), host.intersect(guest.toSet()).toList())
    }

    @Test
    fun theSasIsAlwaysFiveDigits() {
        assertEquals("00000", SessionKeySchedule.formatSas(byteArrayOf(0, 0, 0, 0)))
        assertEquals(
            5,
            SessionKeySchedule.formatSas(byteArrayOf(-1, -1, -1, -1)).length,
            "0xffffffff mod 100000 must still be padded to five",
        )
        assertTrue(SessionKeySchedule.formatSas(byteArrayOf(-1, -1, -1, -1)).all { it.isDigit() })
    }
}
