package com.example.link

import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * Everything the guest device needs in order to join the host's session (issue #33,
 * "The QR payload"). Three encodings carry it, and all three parse back to this:
 *
 * ```
 * https://<host>/j#s=<sid>&k=<qr>&p=<hostPub>&l=<hostLang>&v=1
 * uitr:v1:<sid>:<qr>:<hostPub>:<hostLang>
 * <6-char code>
 * ```
 *
 * The URL form puts **all** of it in the fragment, which no browser ever sends to a
 * server — that is what lets the same QR open the deferred web-guest path later
 * without the relay ever seeing the secret. [qrSecret] is *the* value in this design
 * that must never reach the relay, a path, a query string or a log line, hence the
 * redacting [toString] below and the deliberate absence of any `encodeToQuery`.
 *
 * [qrSecret] and [hostPublicKey] are nullable here because issue #33 lands before
 * issue #34: the transport is built and tested first, and the fields it will need
 * are already on the type so that adding encryption extends this rather than
 * rewriting it. On the manual-code path they stay null by nature — see [ManualCode].
 */
data class SessionTicket(
    /** Relay session id: 16 bytes, base64url, 22 characters. */
    val sessionId: String,
    /** The host's language code, when the payload carried one. `hello` also announces it. */
    val hostLang: String? = null,
    val version: Int = PROTOCOL_VERSION,
    /** #34: the 256-bit QR secret. Never logged, never in a URL path or query. */
    val qrSecret: String? = null,
    /** #34: the host's ephemeral P-256 public key, 65 bytes uncompressed. */
    val hostPublicKey: String? = null,
    /** Set when this ticket came from — or can be shown as — a typed six-character code. */
    val manualCode: String? = null,
) {
    /** True when this ticket was derived from a typed code rather than scanned. */
    val isManual: Boolean get() = manualCode != null

    /** `https://<host>/j#s=…` — the form that also works as an Android App Link. */
    fun encodeToUrl(baseUrl: String): String = buildString {
        append(baseUrl.trimEnd('/'))
        append("/j#s=")
        append(sessionId)
        qrSecret?.let { append("&k=").append(it) }
        hostPublicKey?.let { append("&p=").append(it) }
        hostLang?.let { append("&l=").append(it) }
        append("&v=").append(version)
    }

    /** `uitr:v1:<sid>:<qr>:<hostPub>:<hostLang>` — no host needed, so the QR is smaller. */
    fun encodeCompact(): String =
        "$SCHEME:v$version:$sessionId:${qrSecret.orEmpty()}:${hostPublicKey.orEmpty()}:${hostLang.orEmpty()}"

    /**
     * Deliberately redacted. A ticket reaches `Log.d` the moment anyone prints a
     * state object that holds one, and the whole design of the fragment-only payload
     * is undone by a single careless log line.
     */
    override fun toString(): String =
        "SessionTicket(sessionId=$sessionId, hostLang=$hostLang, version=$version, " +
            "qrSecret=${redact(qrSecret)}, hostPublicKey=${redact(hostPublicKey)}, manualCode=$manualCode)"

    private fun redact(value: String?) = if (value == null) "null" else "<REDACTED>"

    companion object {
        const val PROTOCOL_VERSION = 1
        const val SCHEME = "uitr"

        /** Session ids are 16 bytes: 128 bits, which is the design's number. */
        const val SESSION_ID_BYTES = 16
        const val SESSION_ID_LENGTH = 22

        /** #34: a 256-bit QR secret and a 65-byte uncompressed P-256 point. */
        const val QR_SECRET_BYTES = 32
        const val PUBLIC_KEY_BYTES = 65

        private const val MEMBER_ID_BYTES = 12

        /**
         * The manual path's session id is *derived* from the code rather than random,
         * because the guest types six characters and must arrive at the same session
         * the host is sitting in, and the relay deliberately holds no code→session
         * table to enumerate. The id therefore inherits the code's 30 bits, which is
         * exactly the property issue #34 §5 accounts for: on this path confidentiality
         * rests on ECDH and the user comparing the SAS, not on the id being unguessable.
         * The QR path does not pay that cost — its id is 128 random bits.
         */
        private val MANUAL_TAG = byteArrayOf(0x6D, 0x63, PROTOCOL_VERSION.toByte()) // "mc" + version

        /** A fresh 128-bit session id from the platform CSPRNG. */
        fun newSessionId(): String = Base64Url.encode(CryptographyRandom.nextBytes(SESSION_ID_BYTES))

        /**
         * A per-session random device id for the relay's `?m=`. Not an identity and not
         * a secret: it exists so the relay can fan a frame out to *the other* member.
         * Regenerated every session, so it cannot link one conversation to another.
         */
        fun newMemberId(): String = Base64Url.encode(CryptographyRandom.nextBytes(MEMBER_ID_BYTES))

        /** A fresh six-character pairing code. */
        fun newManualCode(): String =
            ManualCode.format(CryptographyRandom.nextLong(ManualCode.SPACE))

        /** The ticket a typed [code] resolves to, or null when it is not a valid code. */
        fun forManualCode(code: String, hostLang: String? = null): SessionTicket? {
            val value = ManualCode.parseOrNull(code) ?: return null
            val bytes = ByteArray(SESSION_ID_BYTES)
            MANUAL_TAG.copyInto(bytes)
            for (i in 0 until 4) {
                bytes[SESSION_ID_BYTES - 1 - i] = ((value shr (8 * i)) and 0xff).toByte()
            }
            return SessionTicket(
                sessionId = Base64Url.encode(bytes),
                hostLang = hostLang,
                manualCode = ManualCode.format(value),
            )
        }

        /**
         * Parses any of the three payload forms, or returns null when [text] is not one
         * of them or carries a field of the wrong shape. Null rather than an exception:
         * the input is whatever the camera decoded or the user typed.
         */
        fun parse(text: String): SessionTicket? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            return when {
                trimmed.startsWith("$SCHEME:", ignoreCase = true) -> parseCompact(trimmed)
                "#" in trimmed -> parseUrl(trimmed)
                else -> forManualCode(trimmed)
            }
        }

        private fun parseUrl(text: String): SessionTicket? {
            // Only the fragment is read. A payload that put the secret in the query
            // string is not a payload we produced, and honouring it would legitimise
            // the one thing this design forbids.
            val fragment = text.substringAfter('#', "")
            if (fragment.isEmpty()) return null
            val fields = mutableMapOf<String, String>()
            for (pair in fragment.split('&')) {
                if (pair.isEmpty()) continue
                val name = pair.substringBefore('=', "")
                val value = pair.substringAfter('=', "")
                if (name.isNotEmpty()) fields[name] = value
            }
            return build(
                sessionId = fields["s"],
                qrSecret = fields["k"],
                hostPublicKey = fields["p"],
                hostLang = fields["l"],
                version = fields["v"],
            )
        }

        private fun parseCompact(text: String): SessionTicket? {
            val parts = text.split(':')
            if (parts.size != 6) return null
            val version = parts[1].removePrefix("v").removePrefix("V")
            return build(
                sessionId = parts[2],
                qrSecret = parts[3],
                hostPublicKey = parts[4],
                hostLang = parts[5],
                version = version,
            )
        }

        private fun build(
            sessionId: String?,
            qrSecret: String?,
            hostPublicKey: String?,
            hostLang: String?,
            version: String?,
        ): SessionTicket? {
            if (sessionId.isNullOrEmpty()) return null
            if (sessionId.length != SESSION_ID_LENGTH ||
                !Base64Url.isEncodingOf(sessionId, SESSION_ID_BYTES)
            ) {
                return null
            }
            val parsedVersion = if (version.isNullOrEmpty()) PROTOCOL_VERSION else version.toIntOrNull()
            if (parsedVersion != PROTOCOL_VERSION) return null

            val secret = qrSecret?.takeIf { it.isNotEmpty() }
            if (secret != null && !Base64Url.isEncodingOf(secret, QR_SECRET_BYTES)) return null

            val publicKey = hostPublicKey?.takeIf { it.isNotEmpty() }
            if (publicKey != null) {
                val bytes = Base64Url.decodeOrNull(publicKey) ?: return null
                // Uncompressed only: issue #34 F3 settled on the 65-byte 0x04‖X‖Y form
                // because that is what WebCrypto's importKey("raw") accepts unchanged.
                if (bytes.size != PUBLIC_KEY_BYTES || bytes[0] != 0x04.toByte()) return null
            }

            val lang = hostLang?.takeIf { it.isNotEmpty() }
            if (lang != null && !isLanguageCodeShaped(lang)) return null

            return SessionTicket(
                sessionId = sessionId,
                hostLang = lang,
                version = parsedVersion,
                qrSecret = secret,
                hostPublicKey = publicKey,
            )
        }

        /**
         * Shape only — whether the code names a language this build supports is the
         * view model's question, not the wire's, and answering it here would make an
         * older peer's unknown language a parse failure instead of a graceful one.
         */
        private fun isLanguageCodeShaped(code: String): Boolean =
            code.length in 2..8 && code.all { it in 'a'..'z' || it in 'A'..'Z' || it == '-' }
    }
}
