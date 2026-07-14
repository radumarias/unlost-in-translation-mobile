package com.example.model

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

/**
 * A stored translation — the domain model shared across platforms (design §2).
 * Decouples the UI / view model / repository from the Android Room entity;
 * platform storage maps to/from this. Field names/semantics match the former
 * TranslationEntity so history renders identically.
 *
 * Serializable via the hand-written [TranslationSerializer] (not the `@Serializable`
 * plugin) so the web build can persist the history list to localStorage as JSON
 * (design §7). The annotation is deliberately avoided: the kotlinx.serialization
 * compiler plugin crashes (FIR `createDeprecatedHiddenAnnotation` NPE) when it
 * runs alongside the Compose compiler plugin on the wasmJs target, and the shared
 * Compose UI (design §1) forces both plugins onto commonMain. Hand-written
 * serializers keep the model serializable without invoking that plugin on it.
 */
data class Translation(
    val id: Long = 0,
    val sourceText: String,
    val translatedText: String,
    val sourceLangCode: String,
    val sourceLangName: String,
    val targetLangCode: String,
    val targetLangName: String,
    val timestamp: Long,
    val isStarred: Boolean = false,
)

/**
 * Hand-written [KSerializer] for [Translation]. Mirrors what `@Serializable` would
 * generate: a class descriptor with the nine properties in declaration order,
 * `id`/`isStarred` optional (they have defaults). See [Translation] for why the
 * annotation is not used.
 */
object TranslationSerializer : KSerializer<Translation> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.example.model.Translation") {
            element<Long>("id", isOptional = true)
            element<String>("sourceText")
            element<String>("translatedText")
            element<String>("sourceLangCode")
            element<String>("sourceLangName")
            element<String>("targetLangCode")
            element<String>("targetLangName")
            element<Long>("timestamp")
            element<Boolean>("isStarred", isOptional = true)
        }

    override fun serialize(encoder: Encoder, value: Translation) {
        encoder.encodeStructure(descriptor) {
            encodeLongElement(descriptor, 0, value.id)
            encodeStringElement(descriptor, 1, value.sourceText)
            encodeStringElement(descriptor, 2, value.translatedText)
            encodeStringElement(descriptor, 3, value.sourceLangCode)
            encodeStringElement(descriptor, 4, value.sourceLangName)
            encodeStringElement(descriptor, 5, value.targetLangCode)
            encodeStringElement(descriptor, 6, value.targetLangName)
            encodeLongElement(descriptor, 7, value.timestamp)
            encodeBooleanElement(descriptor, 8, value.isStarred)
        }
    }

    override fun deserialize(decoder: Decoder): Translation = decoder.decodeStructure(descriptor) {
        var id = 0L
        var sourceText = ""
        var translatedText = ""
        var sourceLangCode = ""
        var sourceLangName = ""
        var targetLangCode = ""
        var targetLangName = ""
        var timestamp = 0L
        var isStarred = false
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                0 -> id = decodeLongElement(descriptor, 0)
                1 -> sourceText = decodeStringElement(descriptor, 1)
                2 -> translatedText = decodeStringElement(descriptor, 2)
                3 -> sourceLangCode = decodeStringElement(descriptor, 3)
                4 -> sourceLangName = decodeStringElement(descriptor, 4)
                5 -> targetLangCode = decodeStringElement(descriptor, 5)
                6 -> targetLangName = decodeStringElement(descriptor, 6)
                7 -> timestamp = decodeLongElement(descriptor, 7)
                8 -> isStarred = decodeBooleanElement(descriptor, 8)
                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException("Unexpected index $index")
            }
        }
        Translation(
            id = id,
            sourceText = sourceText,
            translatedText = translatedText,
            sourceLangCode = sourceLangCode,
            sourceLangName = sourceLangName,
            targetLangCode = targetLangCode,
            targetLangName = targetLangName,
            timestamp = timestamp,
            isStarred = isStarred,
        )
    }
}
