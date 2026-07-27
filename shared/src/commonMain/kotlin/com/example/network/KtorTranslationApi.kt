package com.example.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import com.example.model.Language
import com.example.model.Situation
import com.example.model.Tone
import com.example.model.TranslationContext
import com.example.platform.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Ktor + kotlinx.serialization implementation of [TranslationApi] (Phase 1 task
 * 4), replacing the OkHttp/org.json `TranslationService`. One [HttpClient] with
 * the OkHttp engine; per-request timeouts (30s Google / 60s Gemini) mirror the
 * two OkHttp clients that existed before. The two prompts and the safety
 * settings are copied verbatim — they are product behavior. Lives in commonMain;
 * the platform injects the [client] (built from a platform HTTP engine via
 * [translationHttpClient]) — OkHttp on Android, Js on wasm (design §5). [apiKey]
 * is the Gemini key for the platform. [log] (optional) receives the per-attempt
 * debug trail of the Gemini retry/fallback policy (issue #3).
 */
class KtorTranslationApi(
    private val apiKey: String,
    private val client: HttpClient,
    private val log: AppLog? = null,
) : TranslationApi {

    companion object {
        private const val TAG = "KtorTranslationApi"
        private const val GEMINI_MODEL = "gemini-3.5-flash"

        /**
         * Sibling model tried when [GEMINI_MODEL] keeps failing (issue #3):
         * capacity spikes rarely hit both models at once, and the free tier's
         * daily allowance is counted per model, so the fallback has its own.
         * Issue #3 names `gemini-3-flash`, but that id does not exist in the
         * live API — this is its actual id (verified against ListModels and
         * with a live generateContent call, 2026-07-18).
         */
        private const val GEMINI_FALLBACK_MODEL = "gemini-3-flash-preview"
        private const val GEMINI_PLACEHOLDER_KEY = "MY_GEMINI_API_KEY"

        private fun geminiUrl(model: String): String =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

        /** Google's documented header alternative to the `?key=` query parameter. */
        private const val GEMINI_API_KEY_HEADER = "x-goog-api-key"
        private const val GOOGLE_TIMEOUT_MS = 30_000L
        private const val GEMINI_TIMEOUT_MS = 60_000L

        /** Total Gemini attempts (1 initial + 2 retries) before giving up. */
        private const val GEMINI_MAX_ATTEMPTS = 3

        /** Backoff before the first retry; doubles for each subsequent one. */
        private const val GEMINI_RETRY_BASE_DELAY_MS = 500L

        /**
         * Marker Gemini puts in a 429 body when the *quota* is gone (the free
         * tier's daily request allowance) rather than a short burst limit.
         */
        private const val GEMINI_QUOTA_EXHAUSTED_STATUS = "RESOURCE_EXHAUSTED"

        /** Shown instead of a bare "429" when the daily allowance is spent. */
        const val GEMINI_QUOTA_MESSAGE =
            "Gemini's daily free quota is used up — translation still works, but AI validation resumes tomorrow."

        /**
         * Whether a failed Gemini call is worth another attempt.
         *
         * 5xx are transient server faults: retry. A 429 needs the body to tell
         * the two cases apart — a burst limit clears in seconds and is worth
         * retrying, but an exhausted daily quota (RESOURCE_EXHAUSTED) does not
         * clear until the quota window rolls over, so retrying only burns more
         * of an allowance that is already gone. Measured against the live API:
         * retrying an exhausted free-tier quota tripled request volume (9 -> 54)
         * and turned a partly-working session into an entirely failing one.
         * Everything else — 400s, auth failures — fails identically on a retry.
         */
        private fun isRetryable(status: HttpStatusCode, body: String): Boolean = when {
            status.value in 500..599 -> true
            status == HttpStatusCode.TooManyRequests ->
                !body.contains(GEMINI_QUOTA_EXHAUSTED_STATUS)
            else -> false
        }

        /**
         * Whether [GEMINI_FALLBACK_MODEL] is worth trying after [GEMINI_MODEL]'s
         * final failure (issue #3): a 404 means this model id is unknown, a 429
         * is a per-model allowance (burst or the daily quota — the sibling has
         * its own), and 5xx capacity spikes rarely hit both models at once.
         * Plain client errors (400/401/403) fail identically on every model.
         */
        private fun fallbackWorthTrying(status: HttpStatusCode): Boolean =
            status == HttpStatusCode.NotFound ||
                status == HttpStatusCode.TooManyRequests ||
                status.value in 500..599

        /**
         * The user-facing message for a failed Gemini response: an exhausted
         * quota gets a plain-language explanation instead of a raw status code,
         * and every other failure carries Google's own human-readable
         * `error.message` where the body has one (issue #3 — a bare status code
         * reads like a broken app).
         */
        private fun geminiErrorMessage(status: HttpStatusCode, body: String): String = when {
            status == HttpStatusCode.TooManyRequests && body.contains(GEMINI_QUOTA_EXHAUSTED_STATUS) ->
                GEMINI_QUOTA_MESSAGE
            status == HttpStatusCode.ServiceUnavailable ->
                "Gemini unavailable (503): ${errorDetail(body)}"
            else ->
                "Gemini error (${status.value}): ${errorDetail(body)}"
        }

        private fun errorDetail(body: String): String =
            GeminiErrorParser.errorMessage(body) ?: body.take(200).ifBlank { "no response body" }

        /**
         * Renders a [TranslationContext] as the prompt block the web build uses
         * (`api/translate/route.ts`) — prior turns first, then the tone and
         * situation constraints, all verbatim because they are product behavior
         * (design §5). Defaults emit nothing, so an unconfigured translation
         * sends exactly the prompt it sent before this existed.
         *
         * A situation deliberately outranks a tone: "absolute precision for
         * Medical" has to beat "Casual", which is why its line carries the
         * STRICTLY-prioritize wording and the tone's does not.
         *
         * Returns the bare lines with no surrounding whitespace; callers decide
         * the separators, so a default context leaves their prompt byte-identical
         * to what it was before this existed.
         */
        internal fun contextBlock(context: TranslationContext): String {
            val lines = mutableListOf<String>()
            if (context.history.isNotEmpty()) {
                // The web build always interpolates this section and falls back
                // to "No prior history."; here an empty history emits nothing at
                // all, so an unconstrained prompt stays exactly as it was.
                lines += "Here is the conversation history so far:"
                context.history.mapTo(lines) { "${it.speakerLangName} Speaker: ${it.text}" }
                lines += "Use the conversation history for context if needed."
            }
            if (context.tone != Tone.AUTO) {
                lines += "IMPORTANT: The requested conversation tone is \"${context.tone}\". " +
                    "The translation MUST reflect this tone accurately."
            }
            if (context.situation != Situation.GENERAL) {
                lines += "IMPORTANT: The requested situation/context is \"${context.situation}\". " +
                    "STRICTLY prioritize vocabulary and politeness suited for this situation " +
                    "(e.g. absolute precision for Medical, rapid concise translation for Emergency)."
            }
            return lines.joinToString("\n")
        }
    }

    override suspend fun translateGoogle(text: String, sourceLang: String, targetLang: String): String {
        if (text.isBlank()) return ""
        return apiCall { GoogleResponseParser.extract(googleBody(text, sourceLang, targetLang)) }
    }

    override suspend fun detectAndTranslateGoogle(text: String, targetLang: String): DetectedTranslation {
        if (text.isBlank()) return DetectedTranslation("", "")
        // `sl=auto` is the same free endpoint asking it to work the source out;
        // the detected code comes back in the response, so auto-detect costs no
        // Gemini request at all.
        return apiCall {
            GoogleResponseParser.extractWithDetection(googleBody(text, Language.AUTO_CODE, targetLang))
        }
    }

    /** One Google request, returning the raw body. Callers pick the parser. */
    private suspend fun googleBody(text: String, sourceLang: String, targetLang: String): String {
        val url = URLBuilder("https://translate.googleapis.com/translate_a/single").apply {
            parameters.append("client", "gtx")
            parameters.append("sl", sourceLang)
            parameters.append("tl", targetLang)
            parameters.append("dt", "t")
            parameters.append("q", text)
        }.buildString()

        val response = client.get(url) {
            header("User-Agent", "Mozilla/5.0")
            timeout { requestTimeoutMillis = GOOGLE_TIMEOUT_MS }
        }
        if (!response.status.isSuccess()) {
            throw Exception("HTTP error code: ${response.status.value}")
        }
        return response.bodyAsText()
    }

    override suspend fun translateGemini(
        text: String,
        sourceLangName: String,
        targetLangName: String,
        context: TranslationContext,
    ): String {
        if (text.isBlank()) return ""
        requireApiKey()

        val instruction = "Translate the following text from $sourceLangName to $targetLangName. Please deliver only the translated text, with no preamble, explanations, decorative markers, or extra words."
        val constraints = contextBlock(context)
        // With no constraints this is the original one-line prompt, unchanged.
        val prompt = if (constraints.isEmpty()) {
            "$instruction Text to translate:\n$text"
        } else {
            "$instruction\n\n$constraints\n\nText to translate:\n$text"
        }
        val request = GeminiRequest(
            contents = listOf(GeminiContent(parts = listOf(GeminiPart(prompt)))),
            safetySettings = GEMINI_SAFETY_SETTINGS,
        )

        return apiCall {
            val result = postGemini(request)
            if (!result.isSuccess) {
                throw Exception(result.errorMessage())
            }
            val parsed = result.response.body<GeminiResponse>()
            extractGeminiText(parsed).trim()
        }
    }

    override suspend fun detectAndTranslateGemini(
        text: String,
        targetLangName: String,
        context: TranslationContext,
    ): DetectedTranslation {
        if (text.isBlank()) return DetectedTranslation("", "")
        requireApiKey()

        val instruction = "Detect the language of the following text and translate it to $targetLangName."
        val schema = """
            Return ONLY a valid JSON object matching this schema, with no markdown wrappers or extra text:
            {
              "detectedSourceLanguage": "The ISO 639-1 code of the language the text is written in (e.g. \"es\").",
              "translation": "The $targetLangName translation, with no preamble, explanations, decorative markers, or extra words."
            }
        """.trimIndent()

        val constraints = contextBlock(context)
        val prompt = instruction +
            (if (constraints.isEmpty()) "" else "\n\n$constraints") +
            "\n\n" + schema +
            "\n\nText to translate:\n$text"

        val request = GeminiRequest(
            contents = listOf(GeminiContent(parts = listOf(GeminiPart(prompt)))),
            safetySettings = GEMINI_SAFETY_SETTINGS,
            generationConfig = GeminiGenerationConfig(responseMimeType = "application/json"),
        )

        return apiCall {
            val result = postGemini(request)
            if (!result.isSuccess) {
                throw Exception(result.errorMessage())
            }
            GeminiDetectionParser.parseDetection(extractGeminiText(result.response.body<GeminiResponse>()))
        }
    }

    override suspend fun validateTranslation(
        originalText: String,
        translatedText: String,
        sourceLangName: String,
        targetLangName: String,
        context: TranslationContext,
    ): ValidationResult {
        requireApiKey()

        // Built in two trimIndent() halves so the (unindented) constraint block
        // can sit between them: a single raw string would make trimIndent see a
        // zero-indent line and stop trimming the rest of the prompt.
        val header = """
            Analyze the following translation for cultural appropriateness, literal mistranslation of idioms, and tone.
            Source Language: $sourceLangName
            Original Text: "$originalText"
            Target Language: $targetLangName
            Translated Text: "$translatedText"
        """.trimIndent()

        val schema = """
            Return ONLY a valid JSON object matching this schema, with no markdown wrappers or extra text:
            {
              "isWarning": boolean,
              "explanation": "Required. Always provide context on the meaning, cultural nuance, and literal translation. If perfectly fine (isWarning: false), explain the meaning positively.",
              "sanityCheck": "Required. A roundtrip back-translation explaining exactly how the $targetLangName speaker will perceive the translation, written in $sourceLangName.",
              "idiomExplanation": "If the Original Text contains an idiom, slang, or a culturally specific concept: explain its meaning, and suggest how a native $targetLangName speaker would naturally express that same idea (the local equivalent idiom or phrase). Do this even if the meaning translated successfully. If no idiom or slang is present, null.",
              "suggestions": [
                 { "tone": "Polite", "text": "Alternative phrase in the Source Language ($sourceLangName)" },
                 { "tone": "Casual", "text": "Alternative phrase in the Source Language ($sourceLangName)" },
                 { "tone": "Direct", "text": "Alternative phrase in the Source Language ($sourceLangName)" }
              ] // Provide alternative translations in different tones if context helps. PLEASE NOTE: the text field MUST be in the Source Language ($sourceLangName).
            }
        """.trimIndent()

        val constraints = contextBlock(context)
        val prompt = header + (if (constraints.isEmpty()) "" else "\n\n$constraints") + "\n\n" + schema

        val request = GeminiRequest(
            contents = listOf(GeminiContent(parts = listOf(GeminiPart(prompt)))),
            safetySettings = GEMINI_SAFETY_SETTINGS,
            generationConfig = GeminiGenerationConfig(responseMimeType = "application/json"),
        )

        return apiCall {
            val result = postGemini(request)
            if (!result.isSuccess) {
                throw Exception(result.errorMessage())
            }
            val parsed = result.response.body<GeminiResponse>()
            GeminiParser.parseValidation(extractGeminiText(parsed))
        }
    }

    /**
     * Image OCR + translation (web-feature port of `api/vision`). The three
     * system prompts are the web build's, minus its target-auto-detect variant
     * — a target language is always chosen here. The supported-language list
     * the model must pick its answer from is this app's sixteen rather than the
     * web's thirteen, and it asks for an ISO code (see [GeminiVisionParser]).
     */
    override suspend fun translateImage(
        imageBase64: String,
        mimeType: String,
        userLangName: String,
        otherLangName: String,
        isSourceAutoDetect: Boolean,
    ): ImageTranslationResult {
        if (imageBase64.isBlank()) throw Exception("The image could not be read.")
        requireApiKey()

        val supported = Language.supportedLanguages.joinToString(", ") { it.name }
        val systemPrompt = if (isSourceAutoDetect) {
            """
            You are a helpful translation assistant.
            You must analyze the image, detect the language of any prominent text, and transcribe it.
            Translate the text to $otherLangName. The detected language can be any language.

            CRITICAL: You must provide a brief explanation if the image is a specific food, sign, or cultural item. The translation AND this explanation MUST be written entirely in $otherLangName.
            """.trimIndent()
        } else {
            """
            You are a helpful translation assistant in a conversation between a $userLangName speaker and a $otherLangName speaker.
            You must analyze the image, detect the language of any prominent text, and transcribe it.
            - If the text in the image is in $userLangName, translate it to $otherLangName.
            - If the text is in $otherLangName or any other language, translate it to $userLangName.

            CRITICAL: You must provide a brief explanation if the image is a specific food, sign, or cultural item. The translation AND this explanation MUST be written entirely in the target language you are translating to.
            """.trimIndent()
        }

        val schema = """
            Return ONLY a valid JSON object matching this schema, with no markdown wrappers or extra text:
            {
              "detectedLanguage": "The ISO 639-1 code of the language of the text in the image (e.g. \"es\"). Prefer one of: $supported.",
              "originalText": "A short transcription of the text found in the image, in its original language.",
              "translation": "The translation and brief explanation, in the appropriate target language per your instructions."
            }
        """.trimIndent()

        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(
                        GeminiPart(text = "$systemPrompt\n\n$schema\n\nPlease translate and explain this image."),
                        GeminiPart(inlineData = GeminiInlineData(mimeType = mimeType, data = imageBase64)),
                    ),
                ),
            ),
            safetySettings = GEMINI_SAFETY_SETTINGS,
            generationConfig = GeminiGenerationConfig(responseMimeType = "application/json"),
        )

        return apiCall {
            val result = postGemini(request)
            if (!result.isSuccess) {
                throw Exception(result.errorMessage())
            }
            GeminiVisionParser.parseImageTranslation(extractGeminiText(result.response.body<GeminiResponse>()))
        }
    }

    /**
     * Rewrite of the speaker's own message that resolves a cultural warning
     * (issue: web-feature port). The prompt is the web build's `api/rewrite`
     * copied verbatim; the only adaptation is that the conversation history and
     * the tone/situation lines arrive together through [contextBlock], so they
     * sit after the task line instead of the history sitting further up.
     */
    override suspend fun rewriteMessage(
        originalText: String,
        sourceLangName: String,
        targetLangName: String,
        warning: String,
        direction: String?,
        context: TranslationContext,
    ): RewriteResult {
        if (originalText.isBlank()) throw Exception("There is nothing to rewrite.")
        requireApiKey()

        val task = if (direction == null) {
            "Your task is to provide an initial suggested rewrite to fix the warning, and suggest 2 other alternative directions for rewriting."
        } else {
            "The user has requested you to rewrite their message using the following direction/style: \"$direction\"\n" +
                "Your task is to rewrite the user's message in $sourceLangName according to the requested direction."
        }

        val schema = if (direction == null) {
            """
            Return ONLY a valid JSON object matching this schema, with no markdown wrappers or extra text:
            {
              "rewriteDirection": "A brief description of how you rewrote it (e.g. \"More polite\", \"Avoid offensive slang\").",
              "rewrittenSource": "The rewritten message in $sourceLangName.",
              "alternativeDirections": ["2 alternative ways to rewrite the message (e.g. \"More formal\", \"Adapt to local culture\")."]
            }
            """.trimIndent()
        } else {
            """
            Return ONLY a valid JSON object matching this schema, with no markdown wrappers or extra text:
            {
              "rewrittenSource": "The rewritten message in $sourceLangName."
            }
            """.trimIndent()
        }

        val header = """
            You are a highly skilled cultural interpreter.
            You are facilitating a conversation between two people. One speaks $sourceLangName and the other speaks $targetLangName.

            The active speaker ($sourceLangName Speaker) wants to say: "$originalText"

            This message triggered the following cultural warning: "${warning.ifBlank { "N/A" }}"

            CRITICAL INSTRUCTION: You MUST take this warning into account. Your suggested rewrite MUST completely resolve the issue described in the warning, while preserving the core intent of the message. The rewrite must be culturally safe and natural for a $targetLangName speaker.

            $task
        """.trimIndent()

        val constraints = contextBlock(context)
        val prompt = header + (if (constraints.isEmpty()) "" else "\n\n$constraints") + "\n\n" + schema

        val request = GeminiRequest(
            contents = listOf(GeminiContent(parts = listOf(GeminiPart(prompt)))),
            safetySettings = GEMINI_SAFETY_SETTINGS,
            generationConfig = GeminiGenerationConfig(responseMimeType = "application/json"),
        )

        return apiCall {
            val result = postGemini(request)
            if (!result.isSuccess) {
                throw Exception(result.errorMessage())
            }
            val parsed = result.response.body<GeminiResponse>()
            GeminiRewriteParser.parseRewrite(extractGeminiText(parsed))
        }
    }

    /**
     * Pulls the first candidate's first text part out of a Gemini response,
     * failing with a clear message when either is absent. A 200 can still carry
     * no usable text — the whole response blocked by a safety filter
     * (`candidates` empty), or a candidate cut off before any content
     * (`finishReason` SAFETY/MAX_TOKENS with an empty `parts`). Without this the
     * bare `.first()` threw `NoSuchElementException` ("Collection is empty"),
     * which apiCall surfaced verbatim on the card; this turns it into an
     * explanation the user can act on.
     */
    private fun extractGeminiText(response: GeminiResponse): String {
        val candidate = response.candidates.firstOrNull()
            ?: throw Exception("Gemini returned no result (the request may have been blocked by a content filter).")
        return candidate.content.parts.firstOrNull()?.text
            ?: throw Exception("Gemini returned an empty result (the response may have been cut off).")
    }

    /**
     * Runs a network call, normalizing failures to [Exception] — the
     * [TranslationApi] error contract callers catch. The wasm Js engine
     * surfaces fetch failures as [kotlin.Error] ("Fail to fetch"), which
     * escaped the view model's catch(Exception) and left the UI on
     * "Translating..." forever (Phase 4 task 3 finding); Android's OkHttp
     * engine throws IOException and is unaffected. Exceptions pass through
     * unchanged — including cancellation and Ktor's HttpRequestTimeoutException
     * (an IOException, so callers surface a timeout as an ordinary error) —
     * preserving the verbatim messages the tests pin. `inline` is
     * load-bearing: it lets the non-suspend [block] wrap suspend calls (the
     * lambda body is inlined into the suspend call sites).
     */
    private inline fun <T> apiCall(block: () -> T): T =
        try {
            block()
        } catch (t: Throwable) {
            throw t as? Exception ?: Exception(t.message ?: "Network request failed", t)
        }

    /** A Gemini response plus its already-read body (needed to classify 429s). */
    private class GeminiHttpResult(val response: HttpResponse, val body: String) {
        val isSuccess: Boolean get() = response.status.isSuccess()
        fun errorMessage(): String = geminiErrorMessage(response.status, body)
    }

    /**
     * The shared Gemini execution path (issue #3): [GEMINI_MODEL] with the
     * retry policy of [postGeminiAttempts], then — when the failure is one a
     * sibling model could survive ([fallbackWorthTrying], or the whole attempt
     * budget went to network failures) — [GEMINI_FALLBACK_MODEL] under the same
     * policy. The last model's result is returned whatever its status; callers
     * decide how to fail.
     */
    private suspend fun postGemini(request: GeminiRequest): GeminiHttpResult {
        val primary = try {
            postGeminiAttempts(GEMINI_MODEL, request)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log?.d(TAG, "Gemini $GEMINI_MODEL kept failing on network errors; trying $GEMINI_FALLBACK_MODEL")
            return postGeminiAttempts(GEMINI_FALLBACK_MODEL, request)
        }
        if (primary.isSuccess || !fallbackWorthTrying(primary.response.status)) return primary
        log?.d(TAG, "Gemini $GEMINI_MODEL failed with HTTP ${primary.response.status.value}; trying $GEMINI_FALLBACK_MODEL")
        return postGeminiAttempts(GEMINI_FALLBACK_MODEL, request)
    }

    /**
     * POSTs to one Gemini [model], retrying only failures another attempt can
     * fix — 5xx, 429s that are burst limits rather than an exhausted quota
     * (see [isRetryable]), and network I/O failures (DNS blips, resets,
     * timeouts; surfaced as exceptions, rethrown once the attempt budget is
     * spent) — with exponential backoff. Retries honour the caller's
     * cancellation: [CancellationException] is never swallowed and [delay] is
     * cancellable, so an abandoned pipeline stops retrying instead of spending
     * more of the allowance.
     */
    private suspend fun postGeminiAttempts(model: String, request: GeminiRequest): GeminiHttpResult {
        var attempt = 0
        while (true) {
            attempt++
            val response = try {
                client.post(geminiUrl(model)) {
                    // The key goes in a header, never the query string: Ktor embeds the
                    // full request URL in its timeout exception messages, and those
                    // messages are shown on screen and written to logcat/console — a
                    // key in the URL leaks with every timeout. A header also keeps it
                    // out of referrers and any proxy's access log.
                    header(GEMINI_API_KEY_HEADER, apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                    timeout { requestTimeoutMillis = GEMINI_TIMEOUT_MS }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log?.d(TAG, "Gemini $model attempt $attempt/$GEMINI_MAX_ATTEMPTS: network failure (${t.message})")
                if (attempt >= GEMINI_MAX_ATTEMPTS) throw t
                delay(GEMINI_RETRY_BASE_DELAY_MS shl (attempt - 1))
                continue
            }
            // Read the body once: it classifies the failure, and a response body
            // can only be consumed a single time.
            val body = if (response.status.isSuccess()) "" else runCatching { response.bodyAsText() }.getOrDefault("")
            log?.d(TAG, "Gemini $model attempt $attempt/$GEMINI_MAX_ATTEMPTS: HTTP ${response.status.value}")
            if (body.isNotBlank()) log?.d(TAG, body)
            if (attempt >= GEMINI_MAX_ATTEMPTS || !isRetryable(response.status, body)) {
                return GeminiHttpResult(response, body)
            }
            delay(GEMINI_RETRY_BASE_DELAY_MS shl (attempt - 1))
        }
    }

    private fun requireApiKey() {
        if (apiKey.isBlank() || apiKey == GEMINI_PLACEHOLDER_KEY) {
            throw Exception("Gemini API key is not configured in the Secrets panel.")
        }
    }
}

/**
 * Builds the [HttpClient] used by [KtorTranslationApi] from a platform [engine].
 * Engine-agnostic (the plugin config is identical across platforms); the concrete
 * engine — OkHttp on Android, Js on wasm — is supplied by the platform container.
 */
fun translationHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(ContentNegotiation) {
        json(LenientJson)
    }
    install(HttpTimeout)
}
