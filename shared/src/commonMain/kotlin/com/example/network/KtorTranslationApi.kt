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
import kotlinx.coroutines.delay

/**
 * Ktor + kotlinx.serialization implementation of [TranslationApi] (Phase 1 task
 * 4), replacing the OkHttp/org.json `TranslationService`. One [HttpClient] with
 * the OkHttp engine; per-request timeouts (30s Google / 60s Gemini) mirror the
 * two OkHttp clients that existed before. The Gemini model id, both prompts, the
 * safety settings, and all error messages are copied verbatim — they are product
 * behavior. Lives in commonMain; the platform injects the [client] (built from a
 * platform HTTP engine via [translationHttpClient]) — OkHttp on Android, Js on
 * wasm (design §5). [apiKey] is the Gemini key for the platform.
 */
class KtorTranslationApi(
    private val apiKey: String,
    private val client: HttpClient,
) : TranslationApi {

    companion object {
        private const val GEMINI_MODEL = "gemini-3.5-flash"
        private const val GEMINI_PLACEHOLDER_KEY = "MY_GEMINI_API_KEY"
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
         * The user-facing message for a failed Gemini response: an exhausted
         * quota gets a plain-language explanation instead of a raw status code,
         * which reads like a broken app.
         */
        private fun geminiErrorMessage(status: HttpStatusCode, body: String): String =
            if (status == HttpStatusCode.TooManyRequests && body.contains(GEMINI_QUOTA_EXHAUSTED_STATUS)) {
                GEMINI_QUOTA_MESSAGE
            } else {
                "Gemini server returned error: ${status.value} - $body"
            }
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
     * POSTs to Gemini, retrying only failures another attempt can fix (5xx, and
     * 429s that are burst limits rather than an exhausted quota — see
     * [isRetryable]) with exponential backoff. The final result is returned
     * whatever its status; callers decide how to fail. Retries honour the
     * caller's cancellation: [delay] is cancellable, so an abandoned pipeline
     * stops retrying instead of spending more of the allowance.
     */
    private suspend fun postGemini(request: GeminiRequest): GeminiHttpResult {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent?key=$apiKey"
        var attempt = 0
        while (true) {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(request)
                timeout { requestTimeoutMillis = GEMINI_TIMEOUT_MS }
            }
            attempt++
            // Read the body once: it classifies the failure, and a response body
            // can only be consumed a single time.
            val body = if (response.status.isSuccess()) "" else runCatching { response.bodyAsText() }.getOrDefault("")
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
