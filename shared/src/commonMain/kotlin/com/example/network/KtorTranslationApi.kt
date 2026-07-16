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
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json

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
            val response = postGemini(request)
            if (!response.status.isSuccess()) {
                val errorStr = runCatching { response.bodyAsText() }.getOrNull() ?: "Unknown error"
                throw Exception("Gemini server returned error: ${response.status.value} - $errorStr")
            }
            val parsed = response.body<GeminiResponse>()
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
            val response = postGemini(request)
            if (!response.status.isSuccess()) {
                throw Exception("Gemini server returned error: ${response.status.value}")
            }
            val parsed = response.body<GeminiResponse>()
            GeminiParser.parseValidation(parsed.candidates.first().content.parts.first().text)
        }
    }

    /**
     * Runs a network call, normalizing failures to [Exception] — the
     * [TranslationApi] error contract callers catch. The wasm Js engine
     * surfaces fetch failures as [kotlin.Error] ("Fail to fetch"), which
     * escaped the view model's catch(Exception) and left the UI on
     * "Translating..." forever (Phase 4 task 3 finding); Android's OkHttp
     * engine throws IOException and is unaffected. Exceptions (including
     * cancellation) pass through unchanged, preserving the verbatim error
     * messages the tests pin.
     */
    private inline fun <T> apiCall(block: () -> T): T =
        try {
            block()
        } catch (t: Throwable) {
            throw t as? Exception ?: Exception(t.message ?: "Network request failed", t)
        }

    private suspend fun postGemini(request: GeminiRequest): HttpResponse {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent?key=$apiKey"
        return client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(request)
            timeout { requestTimeoutMillis = GEMINI_TIMEOUT_MS }
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
