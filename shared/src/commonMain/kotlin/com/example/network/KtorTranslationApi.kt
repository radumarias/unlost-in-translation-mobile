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
    }

    override suspend fun translateGoogle(text: String, sourceLang: String, targetLang: String): String {
        if (text.isBlank()) return ""
        val url = URLBuilder("https://translate.googleapis.com/translate_a/single").apply {
            parameters.append("client", "gtx")
            parameters.append("sl", sourceLang)
            parameters.append("tl", targetLang)
            parameters.append("dt", "t")
            parameters.append("q", text)
        }.buildString()

        return apiCall {
            val response = client.get(url) {
                header("User-Agent", "Mozilla/5.0")
                timeout { requestTimeoutMillis = GOOGLE_TIMEOUT_MS }
            }
            if (!response.status.isSuccess()) {
                throw Exception("HTTP error code: ${response.status.value}")
            }
            GoogleResponseParser.extract(response.bodyAsText())
        }
    }

    override suspend fun translateGemini(text: String, sourceLangName: String, targetLangName: String): String {
        if (text.isBlank()) return ""
        requireApiKey()

        val prompt = "Translate the following text from $sourceLangName to $targetLangName. Please deliver only the translated text, with no preamble, explanations, decorative markers, or extra words. Text to translate:\n$text"
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
            parsed.candidates.first().content.parts.first().text.trim()
        }
    }

    override suspend fun validateTranslation(
        originalText: String,
        translatedText: String,
        sourceLangName: String,
        targetLangName: String,
    ): ValidationResult {
        requireApiKey()

        val prompt = """
            Analyze the following translation for cultural appropriateness, literal mistranslation of idioms, and tone.
            Source Language: $sourceLangName
            Original Text: "$originalText"
            Target Language: $targetLangName
            Translated Text: "$translatedText"

            Return ONLY a valid JSON object matching this schema, with no markdown wrappers or extra text:
            {
              "isWarning": boolean,
              "explanation": "Required. Always provide context on the meaning, cultural nuance, and literal translation. If perfectly fine (isWarning: false), explain the meaning positively.",
              "suggestions": [
                 { "tone": "Polite", "text": "Alternative phrase in the Source Language ($sourceLangName)" },
                 { "tone": "Casual", "text": "Alternative phrase in the Source Language ($sourceLangName)" },
                 { "tone": "Direct", "text": "Alternative phrase in the Source Language ($sourceLangName)" }
              ] // Provide alternative translations in different tones if context helps. PLEASE NOTE: the text field MUST be in the Source Language ($sourceLangName).
            }
        """.trimIndent()

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
            GeminiParser.parseValidation(parsed.candidates.first().content.parts.first().text)
        }
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
