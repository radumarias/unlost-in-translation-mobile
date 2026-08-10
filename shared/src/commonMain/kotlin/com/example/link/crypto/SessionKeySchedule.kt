package com.example.link.crypto

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.SHA256

/**
 * The four keys a paired session runs on, and how they are derived (issue #34 §3.4).
 *
 * ```
 * ikm        = qr ‖ dh                              (32 + 32 bytes)
 * salt       = sid                                  (16 bytes)
 * transcript = 0x01 ‖ hostPub(65) ‖ guestPub(65)
 * derive(label, n) = HKDF-SHA256(ikm, salt, info = label ‖ transcript, n)
 * ```
 *
 * Two properties do the work here, and both are worth stating rather than leaving
 * to be re-derived by whoever reads this next:
 *
 * * **A separate key per direction.** Host→guest and guest→host never share a key,
 *   so the two phones cannot collide in the same nonce space no matter how their
 *   counters interleave.
 * * **The transcript is in `info` for every label.** Both public keys are bound into
 *   every derived key, so substituting either one changes all four outputs — which
 *   is exactly what makes the confirmation MAC in §3.5 catch a relay in the middle.
 *
 * Hybrid input: `qr` comes from the QR (which the relay never sees) and `dh` from
 * ECDH (which a passive relay cannot compute). An attacker needs both.
 */
class SessionKeySchedule private constructor(
    /** AES-256-GCM key, host → guest. */
    val hostToGuest: ByteArray,
    /** AES-256-GCM key, guest → host. */
    val guestToHost: ByteArray,
    /** HMAC-SHA256 key for the mandatory confirmation exchange. */
    val confirmation: ByteArray,
    /** Five digits both people compare out loud on the manual-code path. */
    val sas: String,
) {
    companion object {
        const val PROTOCOL_VERSION: Byte = 0x01

        const val KEY_BYTES = 32
        const val SAS_DIGITS = 5

        private const val LABEL_HOST_TO_GUEST = "uitr/v1 h2g"
        private const val LABEL_GUEST_TO_HOST = "uitr/v1 g2h"
        private const val LABEL_CONFIRMATION = "uitr/v1 cfm"
        private const val LABEL_SAS = "uitr/v1 sas"

        /** The SAS is a u32 taken mod this, so it is always five digits wide. */
        private const val SAS_MODULUS = 100_000L

        /**
         * Derives the whole schedule.
         *
         * @param qrSecret the 256-bit QR secret, or the manual code's bytes on the
         *   degraded path — where it contributes ~30 bits and the SAS is what
         *   actually protects the session (§5).
         * @param sharedSecret the raw ECDH X coordinate, 32 bytes.
         * @param sessionId the 16-byte session id, used as the HKDF salt.
         * @param hostPublicKey / @param guestPublicKey 65-byte uncompressed points.
         */
        suspend fun derive(
            qrSecret: ByteArray,
            sharedSecret: ByteArray,
            sessionId: ByteArray,
            hostPublicKey: ByteArray,
            guestPublicKey: ByteArray,
        ): SessionKeySchedule {
            val ikm = qrSecret + sharedSecret
            val transcript = byteArrayOf(PROTOCOL_VERSION) + hostPublicKey + guestPublicKey
            val hkdf = CryptographyProvider.Default.get(HKDF)

            suspend fun derive(label: String, size: Int): ByteArray =
                hkdf.secretDerivation(
                    digest = SHA256,
                    outputSize = size.bytes,
                    salt = sessionId,
                    info = label.encodeToByteArray() + transcript,
                ).deriveSecretToByteArray(ikm)

            return SessionKeySchedule(
                hostToGuest = derive(LABEL_HOST_TO_GUEST, KEY_BYTES),
                guestToHost = derive(LABEL_GUEST_TO_HOST, KEY_BYTES),
                confirmation = derive(LABEL_CONFIRMATION, KEY_BYTES),
                sas = formatSas(derive(LABEL_SAS, 4)),
            )
        }

        /** Four bytes, big-endian, mod 100000, zero-padded — always five digits. */
        internal fun formatSas(bytes: ByteArray): String {
            var value = 0L
            for (byte in bytes) value = (value shl 8) or (byte.toLong() and 0xff)
            return (value % SAS_MODULUS).toString().padStart(SAS_DIGITS, '0')
        }
    }
}
