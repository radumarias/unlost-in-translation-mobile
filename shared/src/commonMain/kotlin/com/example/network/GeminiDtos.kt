package com.example.network

import com.example.ui.viewmodel.Suggestion
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule

/**
 * Lenient JSON: unknown keys ignored (Gemini/Google add fields we don't model).
 *
 * The Gemini request/response DTOs below are serialized by hand-written
 * [KSerializer]s rather than `@Serializable`: the serialization compiler plugin
 * crashes (FIR NPE) when co-resident with the Compose compiler plugin on wasmJs,
 * and the shared Compose UI (design §1) puts both plugins on commonMain. The two
 * types Ktor resolves reflectively — [GeminiRequest] (request body) and
 * [GeminiResponse] (response body) — are registered [contextual] here so Ktor's
 * content negotiation finds them; nested DTO serializers are referenced directly.
 */
val LenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    serializersModule = SerializersModule {
        contextual(GeminiRequest::class, GeminiRequestSerializer)
        contextual(GeminiResponse::class, GeminiResponseSerializer)
    }
}

// --- Gemini request DTOs (contents / safetySettings / generationConfig) ---

data class GeminiRequest(
    val contents: List<GeminiContent>,
    val safetySettings: List<GeminiSafetySetting>,
    val generationConfig: GeminiGenerationConfig? = null,
)

data class GeminiContent(val parts: List<GeminiPart>)

/**
 * One piece of a request or response: either text or, since the image-OCR port,
 * an inline image. Exactly one field is set; the serializer emits only that one,
 * so a text-only request is byte-identical to what it was before images existed.
 */
data class GeminiPart(val text: String? = null, val inlineData: GeminiInlineData? = null)

/** A base64 image sent inline. [data] carries no `data:` URI prefix. */
data class GeminiInlineData(val mimeType: String, val data: String)

data class GeminiSafetySetting(val category: String, val threshold: String)

data class GeminiGenerationConfig(
    val responseMimeType: String,
)

// --- Gemini response DTO ---

data class GeminiResponse(val candidates: List<GeminiCandidate> = emptyList())

data class GeminiCandidate(val content: GeminiContent)

// --- Hand-written serializers (see LenientJson kdoc for why not @Serializable) ---

object GeminiInlineDataSerializer : KSerializer<GeminiInlineData> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("GeminiInlineData") {
            element<String>("mimeType")
            element<String>("data")
        }

    override fun serialize(encoder: Encoder, value: GeminiInlineData) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.mimeType)
            encodeStringElement(descriptor, 1, value.data)
        }
    }

    override fun deserialize(decoder: Decoder): GeminiInlineData = decoder.decodeStructure(descriptor) {
        var mimeType: String? = null
        var data: String? = null
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                0 -> mimeType = decodeStringElement(descriptor, 0)
                1 -> data = decodeStringElement(descriptor, 1)
                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException("Unexpected index $index")
            }
        }
        GeminiInlineData(
            mimeType ?: throw SerializationException("Missing required field 'mimeType' in GeminiInlineData JSON"),
            data ?: throw SerializationException("Missing required field 'data' in GeminiInlineData JSON"),
        )
    }
}

object GeminiPartSerializer : KSerializer<GeminiPart> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("GeminiPart") {
            element<String>("text", isOptional = true)
            element("inlineData", GeminiInlineDataSerializer.descriptor, isOptional = true)
        }

    override fun serialize(encoder: Encoder, value: GeminiPart) {
        encoder.encodeStructure(descriptor) {
            // Only the field that is set: Gemini rejects a part carrying both,
            // and a null "text" alongside inlineData is not the same as absent.
            value.text?.let { encodeStringElement(descriptor, 0, it) }
            value.inlineData?.let { encodeSerializableElement(descriptor, 1, GeminiInlineDataSerializer, it) }
        }
    }

    override fun deserialize(decoder: Decoder): GeminiPart = decoder.decodeStructure(descriptor) {
        var text: String? = null
        var inlineData: GeminiInlineData? = null
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                0 -> text = decodeStringElement(descriptor, 0)
                1 -> inlineData = decodeSerializableElement(descriptor, 1, GeminiInlineDataSerializer)
                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException("Unexpected index $index")
            }
        }
        if (text == null && inlineData == null) {
            throw SerializationException("GeminiPart JSON carries neither 'text' nor 'inlineData'")
        }
        GeminiPart(text, inlineData)
    }
}

object GeminiContentSerializer : KSerializer<GeminiContent> {
    private val partsSerializer = ListSerializer(GeminiPartSerializer)
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("GeminiContent") {
            element("parts", partsSerializer.descriptor)
        }

    override fun serialize(encoder: Encoder, value: GeminiContent) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, partsSerializer, value.parts)
        }
    }

    override fun deserialize(decoder: Decoder): GeminiContent = decoder.decodeStructure(descriptor) {
        var parts: List<GeminiPart>? = null
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                0 -> parts = decodeSerializableElement(descriptor, 0, partsSerializer)
                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException("Unexpected index $index")
            }
        }
        GeminiContent(parts ?: throw SerializationException("Missing required field 'parts' in GeminiContent JSON"))
    }
}

object GeminiSafetySettingSerializer : KSerializer<GeminiSafetySetting> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("GeminiSafetySetting") {
            element<String>("category")
            element<String>("threshold")
        }

    override fun serialize(encoder: Encoder, value: GeminiSafetySetting) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.category)
            encodeStringElement(descriptor, 1, value.threshold)
        }
    }

    override fun deserialize(decoder: Decoder): GeminiSafetySetting = decoder.decodeStructure(descriptor) {
        var category: String? = null
        var threshold: String? = null
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                0 -> category = decodeStringElement(descriptor, 0)
                1 -> threshold = decodeStringElement(descriptor, 1)
                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException("Unexpected index $index")
            }
        }
        GeminiSafetySetting(
            category ?: throw SerializationException("Missing required field 'category' in GeminiSafetySetting JSON"),
            threshold ?: throw SerializationException("Missing required field 'threshold' in GeminiSafetySetting JSON"),
        )
    }
}

object GeminiGenerationConfigSerializer : KSerializer<GeminiGenerationConfig> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("GeminiGenerationConfig") {
            element<String>("responseMimeType")
        }

    override fun serialize(encoder: Encoder, value: GeminiGenerationConfig) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.responseMimeType)
        }
    }

    override fun deserialize(decoder: Decoder): GeminiGenerationConfig = decoder.decodeStructure(descriptor) {
        var responseMimeType: String? = null
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                0 -> responseMimeType = decodeStringElement(descriptor, 0)
                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException("Unexpected index $index")
            }
        }
        GeminiGenerationConfig(
            responseMimeType
                ?: throw SerializationException("Missing required field 'responseMimeType' in GeminiGenerationConfig JSON"),
        )
    }
}

object GeminiRequestSerializer : KSerializer<GeminiRequest> {
    private val contentsSerializer = ListSerializer(GeminiContentSerializer)
    private val safetySerializer = ListSerializer(GeminiSafetySettingSerializer)
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("GeminiRequest") {
            element("contents", contentsSerializer.descriptor)
            element("safetySettings", safetySerializer.descriptor)
            element("generationConfig", GeminiGenerationConfigSerializer.descriptor, isOptional = true)
        }

    override fun serialize(encoder: Encoder, value: GeminiRequest) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, contentsSerializer, value.contents)
            encodeSerializableElement(descriptor, 1, safetySerializer, value.safetySettings)
            if (value.generationConfig != null) {
                encodeSerializableElement(
                    descriptor, 2, GeminiGenerationConfigSerializer, value.generationConfig,
                )
            }
        }
    }

    override fun deserialize(decoder: Decoder): GeminiRequest = decoder.decodeStructure(descriptor) {
        var contents: List<GeminiContent> = emptyList()
        var safetySettings: List<GeminiSafetySetting> = emptyList()
        var generationConfig: GeminiGenerationConfig? = null
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                0 -> contents = decodeSerializableElement(descriptor, 0, contentsSerializer)
                1 -> safetySettings = decodeSerializableElement(descriptor, 1, safetySerializer)
                2 -> generationConfig =
                    decodeSerializableElement(descriptor, 2, GeminiGenerationConfigSerializer)
                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException("Unexpected index $index")
            }
        }
        GeminiRequest(contents, safetySettings, generationConfig)
    }
}

object GeminiCandidateSerializer : KSerializer<GeminiCandidate> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("GeminiCandidate") {
            element("content", GeminiContentSerializer.descriptor)
        }

    override fun serialize(encoder: Encoder, value: GeminiCandidate) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, GeminiContentSerializer, value.content)
        }
    }

    override fun deserialize(decoder: Decoder): GeminiCandidate = decoder.decodeStructure(descriptor) {
        var content: GeminiContent? = null
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                0 -> content = decodeSerializableElement(descriptor, 0, GeminiContentSerializer)
                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException("Unexpected index $index")
            }
        }
        GeminiCandidate(content ?: throw SerializationException("Missing content"))
    }
}

object GeminiResponseSerializer : KSerializer<GeminiResponse> {
    private val candidatesSerializer = ListSerializer(GeminiCandidateSerializer)
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("GeminiResponse") {
            element("candidates", candidatesSerializer.descriptor, isOptional = true)
        }

    override fun serialize(encoder: Encoder, value: GeminiResponse) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, candidatesSerializer, value.candidates)
        }
    }

    override fun deserialize(decoder: Decoder): GeminiResponse = decoder.decodeStructure(descriptor) {
        var candidates: List<GeminiCandidate> = emptyList()
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                0 -> candidates = decodeSerializableElement(descriptor, 0, candidatesSerializer)
                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException("Unexpected index $index")
            }
        }
        GeminiResponse(candidates)
    }
}

/** The four safety categories, all set to BLOCK_NONE — verbatim from the service. */
val GEMINI_SAFETY_SETTINGS: List<GeminiSafetySetting> = listOf(
    "HARM_CATEGORY_HARASSMENT",
    "HARM_CATEGORY_HATE_SPEECH",
    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
    "HARM_CATEGORY_DANGEROUS_CONTENT",
).map { GeminiSafetySetting(it, "BLOCK_NONE") }

/**
 * Parses the free Google Translate (`translate_a/single`) body. The outer array's
 * first element is a list of `[translatedPart, ...]` groups; concatenate element
 * 0 of each group, skipping empty groups — identical to the previous org.json
 * loop, now via kotlinx.serialization's [JsonElement] tree (design §5).
 */
object GoogleResponseParser {
    fun extract(responseBody: String): String {
        val outer = LenientJson.parseToJsonElement(responseBody).jsonArray
        // Unofficial, unversioned endpoint — an empty array or shape change must
        // surface as a parse error (error card via apiCall), not an index crash.
        val firstGroup = outer.getOrNull(0)?.jsonArray
            ?: throw SerializationException("unexpected Google response shape: empty outer array")
        val sb = StringBuilder()
        for (part in firstGroup) {
            val arr = part.jsonArray
            if (arr.isNotEmpty()) {
                // element 0 is the translated text; matches getString(0)
                arr[0].jsonPrimitive.contentOrNull?.let { sb.append(it) }
            }
        }
        return sb.toString()
    }

    /**
     * [extract] plus element 2 of the outer array, which is the source language
     * the endpoint detected (`[[...parts...], null, "en", …]`). Blank when it is
     * absent or not a string — the caller must not guess a language from
     * silence.
     */
    fun extractWithDetection(responseBody: String): DetectedTranslation {
        val text = extract(responseBody)
        val detected = LenientJson.parseToJsonElement(responseBody).jsonArray
            .getOrNull(2)?.let { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        return DetectedTranslation(text, detected)
    }
}

/**
 * Extracts Google's human-readable `error.message` from a Gemini error body
 * (`{"error":{"code":…,"message":…,"status":…}}`), so surfaced failures read in
 * Google's own words instead of a raw JSON dump (issue #3). Null when the body
 * is not that shape — callers fall back to the raw body.
 */
object GeminiErrorParser {
    fun errorMessage(body: String): String? = runCatching {
        LenientJson.parseToJsonElement(body)
            .jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}

/**
 * Parses Gemini's cultural-validation payload. Kept bug-for-bug with the old
 * org.json code: strips an optional Markdown ```json fence, then reads
 * `isWarning` (default false), `explanation` (default ""), and `suggestions`
 * where each element is either an object (`{"tone","text"}`, tone default
 * "Alternative") or a plain string (tone "Alternative"); blank-text entries are
 * dropped (design §3). `sanityCheck` and `idiomExplanation` were added with the
 * web-feature port and are read just as leniently.
 */
object GeminiParser {

    fun stripJsonFences(rawText: String): String {
        var text = rawText.trim()
        if (text.startsWith("```json")) {
            text = text.removePrefix("```json")
        } else if (text.startsWith("```")) {
            text = text.removePrefix("```")
        }
        if (text.endsWith("```")) {
            text = text.removeSuffix("```")
        }
        return text.trim()
    }

    /**
     * The first balanced JSON object in [text], or null if there is none.
     *
     * `responseMimeType=application/json` asks for a bare object and usually gets
     * one, but not always: observed in the browser on 2026-08-10, a good
     * translation came back with a valid object followed by a stray `]`, which
     * `parseToJsonElement` rejects wholesale ("Expected end of the object or
     * comma"). The cultural check was lost over trailing punctuation.
     *
     * String-aware — a `{`, `}` or `\"` inside a value must not move the depth
     * count, and the explanation field is free text a model may well put braces in.
     */
    internal fun firstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** Parses the fenced-or-bare validation text into a [ValidationResult]. */
    fun parseValidation(rawText: String): ValidationResult {
        val inner = stripJsonFences(rawText)
        val root = runCatching { LenientJson.parseToJsonElement(inner).jsonObject }
            // Second chance before giving up: carve out the object and ignore
            // whatever the model wrapped it in. Only on failure, so a clean
            // response still takes the direct path.
            .recoverCatching {
                val carved = firstJsonObject(inner) ?: throw it
                LenientJson.parseToJsonElement(carved).jsonObject
            }
            .getOrThrow()
        return parseValidationObject(root)
    }

    /** Parses an already-decoded validation object (used by tests + fixtures). */
    fun parseValidationObject(root: JsonObject): ValidationResult {
        val isWarning = (root["isWarning"] as? JsonPrimitive)?.booleanOrNull ?: false
        val explanation = (root["explanation"] as? JsonPrimitive)?.contentOrNull ?: ""
        // Both are absent from a model that ignored the added schema keys, and
        // idiomExplanation is explicitly null whenever there is no idiom — read
        // them as leniently as everything else here. JSON null decodes to
        // JsonNull, whose contentOrNull is null, so the blank filter covers it.
        val sanityCheck = (root["sanityCheck"] as? JsonPrimitive)?.contentOrNull ?: ""
        val idiomExplanation = (root["idiomExplanation"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        val suggestions = mutableListOf<Suggestion>()
        (root["suggestions"] as? JsonArray)?.forEach { element ->
            when (element) {
                is JsonObject -> {
                    val tone = (element["tone"] as? JsonPrimitive)?.contentOrNull ?: "Alternative"
                    val text = (element["text"] as? JsonPrimitive)?.contentOrNull ?: ""
                    if (text.isNotBlank()) suggestions.add(Suggestion(tone, text))
                }
                is JsonPrimitive -> {
                    val plain = element.contentOrNull ?: ""
                    if (plain.isNotBlank()) suggestions.add(Suggestion("Alternative", plain))
                }
                else -> { /* nested arrays etc. are ignored, as optJSONObject/optString did */ }
            }
        }
        return ValidationResult(isWarning, explanation, suggestions, sanityCheck, idiomExplanation)
    }
}

/**
 * Parses the detect-and-translate payload (web-feature port of
 * `isSourceAutoDetect`). The web build asks for a language *name* off a fixed
 * list; this asks for the ISO 639-1 code instead, because everything on this
 * side is keyed by code and a name would need mapping back.
 */
object GeminiDetectionParser {

    fun parseDetection(rawText: String): DetectedTranslation {
        val root = LenientJson.parseToJsonElement(GeminiParser.stripJsonFences(rawText)).jsonObject
        val text = (root["translation"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (text.isBlank()) {
            throw SerializationException("Gemini returned no translation.")
        }
        val detected = (root["detectedSourceLanguage"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return DetectedTranslation(text.trim(), detected.trim())
    }
}

/**
 * Parses the image-OCR payload (web-feature port of `api/vision`). Like
 * [GeminiDetectionParser] this asks for an ISO code rather than the web build's
 * language name. A blank translation is rejected: the user pointed a camera at
 * something and an empty card tells them nothing.
 */
object GeminiVisionParser {

    fun parseImageTranslation(rawText: String): ImageTranslationResult {
        val root = LenientJson.parseToJsonElement(GeminiParser.stripJsonFences(rawText)).jsonObject
        val translation = (root["translation"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (translation.isBlank()) {
            throw SerializationException("Gemini found no text to translate in the image.")
        }
        return ImageTranslationResult(
            detectedLangCode = (root["detectedLanguage"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
            originalText = (root["originalText"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
            translation = translation,
        )
    }
}

/**
 * Parses the rewrite payload (web-feature port of `api/rewrite`). Same
 * fence-stripping and lenient reads as [GeminiParser]: a directed rewrite
 * legitimately answers with `rewrittenSource` alone, so the other two fields
 * default to empty rather than failing.
 *
 * A blank `rewrittenSource` is the one thing worth rejecting — there is nothing
 * to offer the user, and a silent empty card reads as a broken app.
 */
object GeminiRewriteParser {

    fun parseRewrite(rawText: String): RewriteResult {
        val inner = GeminiParser.stripJsonFences(rawText)
        val root = LenientJson.parseToJsonElement(inner).jsonObject
        return parseRewriteObject(root)
    }

    fun parseRewriteObject(root: JsonObject): RewriteResult {
        val rewritten = (root["rewrittenSource"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (rewritten.isBlank()) {
            throw SerializationException("Gemini returned no rewritten text.")
        }
        val direction = (root["rewriteDirection"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val alternatives = (root["alternativeDirections"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { alt -> alt.isNotBlank() } }
            .orEmpty()
        return RewriteResult(direction, rewritten, alternatives)
    }
}
