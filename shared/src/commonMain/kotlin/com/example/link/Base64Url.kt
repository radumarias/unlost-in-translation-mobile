package com.example.link

import kotlin.io.encoding.Base64

/**
 * Unpadded base64url — the single encoding used by everything on the link: session
 * ids, and (from issue #34) the QR secret, public keys, MACs and ciphertext.
 *
 * It exists as one object so the choice of alphabet and the absence of padding are
 * made in exactly one place. `=` padding would have to be percent-encoded in a URL
 * and is pure noise in a QR code, so it is dropped; the length is always implied by
 * the field.
 */
internal object Base64Url {
    private val codec = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    fun encode(bytes: ByteArray): String = codec.encode(bytes)

    /**
     * Decodes [text], or returns null when it is not valid unpadded base64url.
     *
     * Null rather than an exception: every caller is parsing something a stranger
     * supplied — a scanned QR, a typed code, a frame off the relay — and none of
     * them has anything useful to do with a stack trace.
     */
    fun decodeOrNull(text: String): ByteArray? {
        if (text.isEmpty() || !text.all { it.isBase64UrlChar() }) return null
        return runCatching { codec.decode(text) }.getOrNull()
    }

    /** True when [text] decodes to exactly [byteLength] bytes. */
    fun isEncodingOf(text: String, byteLength: Int): Boolean =
        decodeOrNull(text)?.size == byteLength

    private fun Char.isBase64UrlChar(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '_'
}
