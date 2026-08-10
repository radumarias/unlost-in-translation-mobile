package com.example.link

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.Json

/**
 * One frame on the link (issue #33, "Wire format": a flat message with a `type`
 * discriminator, not a polymorphic envelope — three message types do not justify a
 * hand-written polymorphic dispatcher, and there is no precedent for one in this
 * repo).
 *
 * Every field beyond the three the header requires is nullable, and the serializer
 * emits only what is set, so a `bye` is nine bytes of JSON rather than a page of
 * nulls. The [pub]/[mac]/[n]/[ct] slots belong to issue #34 and are unused today:
 * they are here from the first version so that turning encryption on extends the
 * wire type instead of changing it, which matters because two phones can be on
 * different app versions.
 *
 * Serialized by a hand-written [KSerializer]: the kotlinx-serialization compiler
 * plugin crashes the FIR compiler next to the Compose plugin on wasmJs, so `:shared`
 * never applies it (see `GeminiDtos.kt`).
 */
data class LinkMessage(
    val type: String,
    /** Per-session random device id. Not a stable identity — see `SessionTicket.newMemberId`. */
    val from: String,
    val v: Int = SessionTicket.PROTOCOL_VERSION,
    /** Sender's language code (`hello`, `turn`). */
    val lang: String? = null,
    /** Optional display name (`hello`). */
    val name: String? = null,
    /** Turn id — what makes replay after a reconnect idempotent (`turn`). */
    val id: String? = null,
    /** What the sender said, in the sender's language (`turn`). */
    val original: String? = null,
    /** Already translated into the receiver's language (`turn`). */
    val translated: String? = null,
    /** Sender's clock. Display only — never used for ordering or freshness. */
    val ts: Long? = null,
    // --- issue #34, unused in #33 ---
    /** Ephemeral P-256 public key, base64url uncompressed (`kex`). */
    val pub: String? = null,
    /** HMAC-SHA256 confirmation tag, base64url (`cfm`). */
    val mac: String? = null,
    /** Record counter, per direction (`enc`). */
    val n: Long? = null,
    /** AES-256-GCM ciphertext, base64url (`enc`). */
    val ct: String? = null,
) {
    companion object {
        const val TYPE_HELLO = "hello"
        const val TYPE_TURN = "turn"
        const val TYPE_BYE = "bye"

        // Reserved for issue #34. Named here so #33 can already recognise — and
        // deliberately ignore — a frame from a peer that has encryption.
        const val TYPE_KEX = "kex"
        const val TYPE_CFM = "cfm"
        const val TYPE_ENC = "enc"

        fun hello(from: String, lang: String, name: String? = null, ts: Long? = null) =
            LinkMessage(type = TYPE_HELLO, from = from, lang = lang, name = name, ts = ts)

        fun turn(
            from: String,
            id: String,
            lang: String,
            original: String,
            translated: String,
            ts: Long? = null,
        ) = LinkMessage(
            type = TYPE_TURN,
            from = from,
            lang = lang,
            id = id,
            original = original,
            translated = translated,
            ts = ts,
        )

        fun bye(from: String, ts: Long? = null) =
            LinkMessage(type = TYPE_BYE, from = from, ts = ts)
    }
}

/**
 * Text in, text out. Both directions return null instead of throwing, because both
 * are driven by a stranger: [decode] parses whatever arrived off the relay, and a
 * malformed frame must cost one dropped message, not the session.
 */
object LinkCodec {
    /** Unknown keys are ignored so a newer peer's extra field is not a hard failure. */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        encodeDefaults = true
    }

    fun encode(message: LinkMessage): String = json.encodeToString(LinkMessageSerializer, message)

    fun decode(text: String): LinkMessage? {
        val message = runCatching { json.decodeFromString(LinkMessageSerializer, text) }.getOrNull()
            ?: return null
        return message.takeIf { it.isWellFormed() }
    }

    /**
     * Whether the frame carries the fields its own type promises. Checked here rather
     * than in the serializer so that the serializer stays a pure JSON mapping and the
     * per-type contract lives next to the factories that produce it.
     *
     * A version mismatch fails: the peer speaks a protocol this build does not.
     * An unrecognised *type* also fails — including issue #34's, which is the point.
     * A #33-only build that received a `kex` and shrugged would carry on in plaintext
     * with a peer that believes the session is encrypted, which is worse than
     * dropping the frame and letting the session time out.
     */
    private fun LinkMessage.isWellFormed(): Boolean {
        if (v != SessionTicket.PROTOCOL_VERSION) return false
        if (from.isEmpty()) return false
        return when (type) {
            LinkMessage.TYPE_HELLO -> !lang.isNullOrEmpty()
            LinkMessage.TYPE_TURN ->
                !id.isNullOrEmpty() && !lang.isNullOrEmpty() && original != null && translated != null
            LinkMessage.TYPE_BYE -> true
            else -> false
        }
    }
}

/** See `GeminiDtos.kt` for why every serializer in `:shared` is written out by hand. */
object LinkMessageSerializer : KSerializer<LinkMessage> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("LinkMessage") {
        element<Int>("v")
        element<String>("type")
        element<String>("from")
        element<String>("lang", isOptional = true)
        element<String>("name", isOptional = true)
        element<String>("id", isOptional = true)
        element<String>("original", isOptional = true)
        element<String>("translated", isOptional = true)
        element<Long>("ts", isOptional = true)
        element<String>("pub", isOptional = true)
        element<String>("mac", isOptional = true)
        element<Long>("n", isOptional = true)
        element<String>("ct", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: LinkMessage) {
        encoder.encodeStructure(descriptor) {
            encodeIntElement(descriptor, 0, value.v)
            encodeStringElement(descriptor, 1, value.type)
            encodeStringElement(descriptor, 2, value.from)
            // Absent, not null: on a relay that charges by the frame there is no reason
            // to spend bytes saying a field is missing.
            value.lang?.let { encodeStringElement(descriptor, 3, it) }
            value.name?.let { encodeStringElement(descriptor, 4, it) }
            value.id?.let { encodeStringElement(descriptor, 5, it) }
            value.original?.let { encodeStringElement(descriptor, 6, it) }
            value.translated?.let { encodeStringElement(descriptor, 7, it) }
            value.ts?.let { encodeLongElement(descriptor, 8, it) }
            value.pub?.let { encodeStringElement(descriptor, 9, it) }
            value.mac?.let { encodeStringElement(descriptor, 10, it) }
            value.n?.let { encodeLongElement(descriptor, 11, it) }
            value.ct?.let { encodeStringElement(descriptor, 12, it) }
        }
    }

    override fun deserialize(decoder: Decoder): LinkMessage = decoder.decodeStructure(descriptor) {
        var v: Int? = null
        var type: String? = null
        var from: String? = null
        var lang: String? = null
        var name: String? = null
        var id: String? = null
        var original: String? = null
        var translated: String? = null
        var ts: Long? = null
        var pub: String? = null
        var mac: String? = null
        var n: Long? = null
        var ct: String? = null
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                0 -> v = decodeIntElement(descriptor, 0)
                1 -> type = decodeStringElement(descriptor, 1)
                2 -> from = decodeStringElement(descriptor, 2)
                3 -> lang = decodeStringElement(descriptor, 3)
                4 -> name = decodeStringElement(descriptor, 4)
                5 -> id = decodeStringElement(descriptor, 5)
                6 -> original = decodeStringElement(descriptor, 6)
                7 -> translated = decodeStringElement(descriptor, 7)
                8 -> ts = decodeLongElement(descriptor, 8)
                9 -> pub = decodeStringElement(descriptor, 9)
                10 -> mac = decodeStringElement(descriptor, 10)
                11 -> n = decodeLongElement(descriptor, 11)
                12 -> ct = decodeStringElement(descriptor, 12)
                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException("Unexpected index $index")
            }
        }
        LinkMessage(
            type = type ?: throw SerializationException("Missing required field 'type' in LinkMessage JSON"),
            from = from ?: throw SerializationException("Missing required field 'from' in LinkMessage JSON"),
            v = v ?: throw SerializationException("Missing required field 'v' in LinkMessage JSON"),
            lang = lang,
            name = name,
            id = id,
            original = original,
            translated = translated,
            ts = ts,
            pub = pub,
            mac = mac,
            n = n,
            ct = ct,
        )
    }
}
