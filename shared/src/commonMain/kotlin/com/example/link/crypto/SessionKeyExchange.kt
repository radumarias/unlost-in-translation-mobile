package com.example.link.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH

/**
 * One side's ephemeral P-256 key pair, and the agreement step (issue #34 §3.3).
 *
 * Ephemeral means exactly that: the private key exists in RAM for the length of one
 * session and is never written anywhere. If the process dies the session is over,
 * which is what keeps this feature free of any key storage — and therefore free of
 * every question that key storage brings with it.
 */
class SessionKeyExchange private constructor(
    private val keyPair: ECDH.KeyPair,
    /** This side's public key, 65 bytes uncompressed — what goes in the QR or `kex`. */
    val publicKey: ByteArray,
) {

    /**
     * The raw ECDH shared secret with [peerPublicKey] (the X coordinate, 32 bytes).
     *
     * @throws LinkCryptoException when the peer's key is malformed, the wrong
     *   length, or not a point on P-256 — all of which mean the session is over,
     *   because there is no plaintext to fall back to.
     */
    suspend fun agree(peerPublicKey: ByteArray): ByteArray {
        requireUncompressedPoint(peerPublicKey)
        // Decoding and agreeing are both inside the catch, deliberately. The two
        // providers reject an off-curve point at *different* steps — the JDK decodes
        // it happily and throws `InvalidKeyException: Point is not on curve` later,
        // from the agreement — so catching only around the decode would let a bad
        // key through on one platform and not the other. This is not theoretical:
        // the off-curve test failed exactly that way before the catch was widened.
        return try {
            val peer = CryptographyProvider.Default.get(ECDH)
                .publicKeyDecoder(EC.Curve.P256)
                .decodeFromByteArray(EC.PublicKey.Format.RAW, peerPublicKey)
            keyPair.privateKey.sharedSecretGenerator().generateSharedSecretToByteArray(peer)
        } catch (t: Throwable) {
            throw LinkCryptoException(
                LinkCryptoException.Reason.BAD_PUBLIC_KEY,
                "the peer's public key is not a valid P-256 point",
                t,
            )
        }
    }

    companion object {
        /** `0x04 ‖ X ‖ Y` — see [requireUncompressedPoint]. */
        const val PUBLIC_KEY_BYTES = 65
        private const val UNCOMPRESSED_TAG: Byte = 0x04

        /** A fresh ephemeral pair. Called once per session, on both sides. */
        suspend fun generate(): SessionKeyExchange {
            val keyPair = CryptographyProvider.Default.get(ECDH)
                .keyPairGenerator(EC.Curve.P256)
                .generateKey()
            return SessionKeyExchange(
                keyPair = keyPair,
                publicKey = keyPair.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW),
            )
        }

        /**
         * Rejects anything that is not the 65-byte uncompressed form *before* it
         * reaches the provider.
         *
         * Issue #34 F3 settled on uncompressed because cryptography-kotlin's
         * WebCrypto provider hands the bytes straight to `importKey("raw", …)`, and
         * browsers do not reliably accept a compressed point there. Checking here
         * turns "works on the JVM, fails on the other person's phone" into a clear
         * failure on the frame that carried it.
         */
        internal fun requireUncompressedPoint(bytes: ByteArray) {
            if (bytes.size != PUBLIC_KEY_BYTES || bytes[0] != UNCOMPRESSED_TAG) {
                throw LinkCryptoException(
                    LinkCryptoException.Reason.BAD_PUBLIC_KEY,
                    "a public key must be $PUBLIC_KEY_BYTES uncompressed bytes",
                )
            }
        }
    }
}
