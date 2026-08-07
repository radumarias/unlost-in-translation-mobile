package com.example.link.crypto

/**
 * Anything that goes wrong in the handshake or the record layer (issue #34 §3.7).
 *
 * One type, on purpose: every one of these ends the session the same way. There is
 * **no plaintext fallback** and no partial-trust state, so nothing downstream ever
 * needs to tell a bad MAC from a failed tag in order to decide what to do — only in
 * order to log it.
 */
class LinkCryptoException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Reason {
        /** A `cfm` MAC that does not verify — the classic relay-in-the-middle. */
        CONFIRMATION_FAILED,

        /** A GCM tag failure: the frame was altered, or the keys do not match. */
        DECRYPTION_FAILED,

        /** A record counter at or below one already seen on that direction. */
        REPLAY,

        /** A public key that is malformed, the wrong length, or not on the curve. */
        BAD_PUBLIC_KEY,

        /** A ticket that must carry a QR secret and does not. */
        MISSING_SECRET,

        /** A frame whose protocol version this build does not implement. */
        BAD_VERSION,
    }
}
