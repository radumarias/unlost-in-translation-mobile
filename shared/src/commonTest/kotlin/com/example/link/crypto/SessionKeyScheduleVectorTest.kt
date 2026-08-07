package com.example.link.crypto

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Committed known-answer vectors for the key schedule.
 *
 * The point is not that HKDF works — it is that **the JDK provider and the WebCrypto
 * provider produce the same bytes**. These expected values are literals in the
 * source, so the JVM run and the ChromeHeadless run are both checked against the
 * same constant rather than against each other. Two phones in one session may be on
 * different platforms, and a schedule that diverged in a browser would be a bug that
 * never reproduces on a laptop.
 *
 * A failure here means one of three things, in order of likelihood: the schedule's
 * inputs were changed (labels, transcript layout, salt), a provider changed
 * behaviour, or the two providers genuinely disagree. The first is a decision to
 * make deliberately — **it is a wire-format break**, and two app versions would no
 * longer be able to pair.
 */
class SessionKeyScheduleVectorTest {

    // Fixed, arbitrary, and deliberately not round numbers.
    private val qrSecret = ByteArray(32) { (it * 7 + 1).toByte() }
    private val sharedSecret = ByteArray(32) { (it * 11 + 3).toByte() }
    private val sessionId = ByteArray(16) { (it * 5 + 2).toByte() }
    private val hostPublicKey = ByteArray(65) { if (it == 0) 0x04 else (it * 13).toByte() }
    private val guestPublicKey = ByteArray(65) { if (it == 0) 0x04 else (it * 17).toByte() }

    @Test
    fun rfc5869TestCase1() = runTest {
        // Anchors the primitive itself before anchoring our use of it: if this fails,
        // the provider is not doing HKDF-SHA256 and nothing below means anything.
        val okm = CryptographyProvider.Default.get(HKDF)
            .secretDerivation(
                digest = SHA256,
                outputSize = 42.bytes,
                salt = ByteArray(13) { it.toByte() },
                info = ByteArray(10) { (0xf0 + it).toByte() },
            )
            .deriveSecretToByteArray(ByteArray(22) { 0x0b })

        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            okm.toHex(),
        )
    }

    @Test
    fun theScheduleDerivesTheseExactBytesOnEveryPlatform() = runTest {
        val schedule = SessionKeySchedule.derive(
            qrSecret = qrSecret,
            sharedSecret = sharedSecret,
            sessionId = sessionId,
            hostPublicKey = hostPublicKey,
            guestPublicKey = guestPublicKey,
        )

        assertEquals(HOST_TO_GUEST, schedule.hostToGuest.toHex())
        assertEquals(GUEST_TO_HOST, schedule.guestToHost.toHex())
        assertEquals(CONFIRMATION, schedule.confirmation.toHex())
        assertEquals(SAS, schedule.sas)
    }

    @Test
    fun changingOneBitOfEitherPublicKeyChangesEveryKey() = runTest {
        // The transcript is bound into `info` for every label, which is what makes
        // the confirmation MAC catch a substituted key.
        val altered = guestPublicKey.copyOf().also { it[1] = (it[1].toInt() xor 1).toByte() }
        val schedule = SessionKeySchedule.derive(
            qrSecret, sharedSecret, sessionId, hostPublicKey, altered
        )

        assertEquals(false, schedule.hostToGuest.toHex() == HOST_TO_GUEST)
        assertEquals(false, schedule.guestToHost.toHex() == GUEST_TO_HOST)
        assertEquals(false, schedule.confirmation.toHex() == CONFIRMATION)
        assertEquals(false, schedule.sas == SAS)
    }

    private fun ByteArray.toHex(): String = joinToString("") {
        val v = it.toInt() and 0xff
        HEX[v shr 4].toString() + HEX[v and 0x0f]
    }

    private companion object {
        const val HEX = "0123456789abcdef"

        // Recorded from the first green run and verified identical on the JVM host
        // and in ChromeHeadless. Changing any of these is a wire-format break.
        const val HOST_TO_GUEST = "d3b1e47d3ce9edbb4d0bb8541f80ea81a80cae5aa8c7175a516b6e4ca3ec3af2"
        const val GUEST_TO_HOST = "50f6e77acd258b56e4587442fb1b57da0e8943d51866073cbf35ebd3f2fe9da4"
        const val CONFIRMATION = "31852336f8d96e88fde793edde88f1bb63a07fd1f982230667b012d1a9d0b860"
        const val SAS = "24079"
    }
}
