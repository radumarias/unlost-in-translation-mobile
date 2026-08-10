package com.example.link.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256

/** Which way a frame is travelling. The nonce's first byte, and the key selector. */
enum class LinkDirection(val wireValue: Int) {
    HOST_TO_GUEST(0),
    GUEST_TO_HOST(1);

    val opposite: LinkDirection get() = if (this == HOST_TO_GUEST) GUEST_TO_HOST else HOST_TO_GUEST
}

/** Which end of the session this device is. */
enum class LinkRole {
    HOST,
    GUEST;

    /** The direction this device sends on. */
    val sending: LinkDirection get() = if (this == HOST) LinkDirection.HOST_TO_GUEST else LinkDirection.GUEST_TO_HOST

    /** The label this device MACs in the confirmation exchange (§3.5). */
    val confirmationLabel: String get() = if (this == HOST) "host" else "guest"
}

/**
 * The record layer: AES-256-GCM over the #33 frames, plus the confirmation MAC
 * (issue #34 §3.5 and §3.6).
 *
 * The encryption **wraps** the existing protocol rather than redesigning it — the
 * plaintext of every record is exactly the `LinkMessage` JSON #33 already sends, so
 * there is one wire format and encryption is a layer over it.
 *
 * The nonce is `dir(1) ‖ 0x00 0x00 0x00 ‖ n(8, big-endian)`. It is fully determined
 * by the direction and the counter, which means nonce reuse — the one mistake that
 * breaks GCM outright — is impossible by construction rather than by remembering to
 * randomise correctly. That is also the justification for opting in to
 * `@DelicateCryptographyApi`: the annotation warns about caller-supplied IVs, and
 * this is the case it is warning about being done deliberately.
 *
 * One instance per session, holding the keys in RAM only.
 */
class SessionCrypto(
    private val schedule: SessionKeySchedule,
    private val role: LinkRole,
) {
    /** The next counter this device will send on. */
    private var sendCounter = 0L

    /** Highest counter accepted per direction; anything at or below it is a replay. */
    private val lastSeen = mutableMapOf<LinkDirection, Long>()

    /** Five digits both people compare on the manual-code path. */
    val sas: String get() = schedule.sas

    /** A sealed record: the counter it was sealed under, and its ciphertext. */
    data class Record(val counter: Long, val ciphertext: ByteArray) {
        // Data classes compare ByteArray by reference; for a value type that goes on
        // a wire and into test assertions that is the wrong answer.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Record && counter == other.counter && ciphertext.contentEquals(other.ciphertext))

        override fun hashCode(): Int = 31 * counter.hashCode() + ciphertext.contentHashCode()
    }

    /** Encrypts [plaintext] from [from] under the next counter. */
    @OptIn(DelicateCryptographyApi::class)
    suspend fun seal(plaintext: ByteArray, from: String): Record {
        val counter = sendCounter++
        val direction = role.sending
        val ciphertext = cipherFor(direction).encryptWithIv(
            iv = nonce(direction, counter),
            plaintext = plaintext,
            associatedData = associatedData(from, counter),
        )
        return Record(counter, ciphertext)
    }

    /**
     * Decrypts a record that arrived from the peer.
     *
     * @throws LinkCryptoException [REPLAY][LinkCryptoException.Reason.REPLAY] when
     *   [counter] is at or below one already accepted on the peer's direction, and
     *   [DECRYPTION_FAILED][LinkCryptoException.Reason.DECRYPTION_FAILED] when the
     *   GCM tag does not verify.
     */
    @OptIn(DelicateCryptographyApi::class)
    suspend fun open(ciphertext: ByteArray, counter: Long, from: String): ByteArray {
        val direction = role.sending.opposite
        val seen = lastSeen[direction]
        // At-or-below is the whole rule, and it does three jobs: it stops a replay,
        // it silently drops the frames the relay's reconnect ring hands back, and it
        // leaves genuine gaps acceptable — GCM does not need contiguity.
        if (seen != null && counter <= seen) {
            throw LinkCryptoException(
                LinkCryptoException.Reason.REPLAY,
                "record $counter was already seen on $direction",
            )
        }
        val plaintext = try {
            cipherFor(direction).decryptWithIv(
                iv = nonce(direction, counter),
                ciphertext = ciphertext,
                associatedData = associatedData(from, counter),
            )
        } catch (t: Throwable) {
            throw LinkCryptoException(
                LinkCryptoException.Reason.DECRYPTION_FAILED,
                "the record did not authenticate",
                t,
            )
        }
        // Only after it authenticates: otherwise anyone able to inject a frame with a
        // high counter could lock the real peer out for the rest of the session.
        lastSeen[direction] = counter
        return plaintext
    }

    /** This device's confirmation MAC, to be sent in `cfm`. */
    suspend fun confirmation(): ByteArray =
        confirmationKey().signatureGenerator()
            .generateSignature(role.confirmationLabel.encodeToByteArray())

    /**
     * Verifies the peer's confirmation MAC.
     *
     * Verified provider-side, so the comparison is constant-time rather than a
     * `contentEquals` that leaks how much of the tag matched.
     *
     * @throws LinkCryptoException when it does not verify — which is what a relay
     *   substituting its own key in the middle looks like from here.
     */
    suspend fun verifyPeerConfirmation(mac: ByteArray) {
        val peerLabel = if (role == LinkRole.HOST) "guest" else "host"
        val ok = confirmationKey().signatureVerifier()
            .tryVerifySignature(peerLabel.encodeToByteArray(), mac)
        if (!ok) {
            throw LinkCryptoException(
                LinkCryptoException.Reason.CONFIRMATION_FAILED,
                "the peer's key confirmation did not verify",
            )
        }
    }

    private suspend fun cipherFor(direction: LinkDirection) =
        CryptographyProvider.Default.get(AES.GCM).keyDecoder()
            .decodeFromByteArray(
                AES.Key.Format.RAW,
                when (direction) {
                    LinkDirection.HOST_TO_GUEST -> schedule.hostToGuest
                    LinkDirection.GUEST_TO_HOST -> schedule.guestToHost
                },
            )
            .cipher()

    private suspend fun confirmationKey() =
        CryptographyProvider.Default.get(HMAC).keyDecoder(SHA256)
            .decodeFromByteArray(HMAC.Key.Format.RAW, schedule.confirmation)

    companion object {
        const val NONCE_BYTES = 12

        /** `dir(1) ‖ 0x00 0x00 0x00 ‖ counter(8, big-endian)` — issue #34 §3.6. */
        internal fun nonce(direction: LinkDirection, counter: Long): ByteArray =
            ByteArray(NONCE_BYTES).also { n ->
                n[0] = direction.wireValue.toByte()
                for (i in 0 until 8) n[11 - i] = ((counter shr (8 * i)) and 0xff).toByte()
            }

        /**
         * `"1|enc|<from>|<n>"` — the header fields travel authenticated but readable,
         * so a relay that rewrites the sender id or the counter of a frame it cannot
         * decrypt still breaks the tag.
         */
        internal fun associatedData(from: String, counter: Long): ByteArray =
            "1|enc|$from|$counter".encodeToByteArray()
    }
}
