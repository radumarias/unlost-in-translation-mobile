package com.example.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end tests for [KtorTranslationApi] using Ktor's MockEngine — no real
 * network. Verifies request/response wiring, the preserved error messages, and
 * the API-key guard (Phase 1 task 4). Common code, so lives in commonTest.
 */
class KtorTranslationApiTest {

    private fun apiReturning(status: HttpStatusCode, body: String, apiKey: String = "test-key"): KtorTranslationApi {
        val engine = MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(LenientJson) }
            install(HttpTimeout)
        }
        return KtorTranslationApi(apiKey = apiKey, client = client)
    }

    private fun geminiTextResponse(text: String): String {
        val escaped = LenientJson.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text))
        return """{"candidates":[{"content":{"parts":[{"text":$escaped}]}}]}"""
    }

    @Test
    fun translateGoogle_parses_body() = runTest {
        val api = apiReturning(HttpStatusCode.OK, "[[[\"Hola\",\"Hello\",null,null,10]],null,\"en\"]")
        assertEquals("Hola", api.translateGoogle("Hello", "en", "es"))
    }

    @Test
    fun translateGoogle_url_encodes_the_query_and_sets_params() = runTest {
        var capturedUrl = ""
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = "[[[\"x\",\"y\"]],null,\"en\"]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(LenientJson) }
            install(HttpTimeout)
        }
        KtorTranslationApi(apiKey = "k", client = client).translateGoogle("a & b?", "en", "es")

        assertTrue(capturedUrl.contains("client=gtx"), capturedUrl)
        assertTrue(capturedUrl.contains("sl=en"), capturedUrl)
        assertTrue(capturedUrl.contains("tl=es"), capturedUrl)
        assertTrue(capturedUrl.contains("dt=t"), capturedUrl)
        // raw '&' / '?' from the text must be percent-encoded, not injected as
        // extra query delimiters
        assertTrue(capturedUrl.contains("q=a"), capturedUrl)
        assertFalse(capturedUrl.contains("b?"), "raw ampersand leaked into query: $capturedUrl")
    }

    @Test
    fun translateGoogle_blank_text_short_circuits() = runTest {
        val api = apiReturning(HttpStatusCode.OK, "unused")
        assertEquals("", api.translateGoogle("   ", "en", "es"))
    }

    @Test
    fun engine_error_is_normalized_to_exception() = runTest {
        // Regression (Phase 4 task 3): the wasm Js engine throws kotlin.Error
        // ("Fail to fetch") when offline. The API contract is "failures are
        // Exceptions" — an Error must not escape past the view model's
        // catch(Exception) and strand the UI on the loading state.
        val engine = MockEngine { throw Error("Fail to fetch") }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(LenientJson) }
            install(HttpTimeout)
        }
        val api = KtorTranslationApi(apiKey = "k", client = client)
        try {
            api.translateGoogle("Hello", "en", "es")
            fail("expected an Exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("Fail to fetch"), "message was: ${e.message}")
        }
    }

    @Test
    fun requestTimeout_surfaces_as_an_ordinary_exception() = runTest {
        // Backlog from the Phase 4 review: pin that Ktor's
        // HttpRequestTimeoutException (an IOException, not a
        // CancellationException) crosses apiCall unchanged and reaches the
        // view model's catch(Exception) as a normal, displayable error.
        val engine = MockEngine { request -> throw HttpRequestTimeoutException(request) }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(LenientJson) }
            install(HttpTimeout)
        }
        val api = KtorTranslationApi(apiKey = "k", client = client)
        try {
            api.translateGoogle("Hello", "en", "es")
            fail("expected a timeout exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("timeout", ignoreCase = true), "message was: ${e.message}")
        }
    }

    @Test
    fun translateGoogle_non_2xx_preserves_HTTP_error_message() = runTest {
        val api = apiReturning(HttpStatusCode.BadGateway, "nope")
        try {
            api.translateGoogle("Hello", "en", "es")
            fail("expected exception")
        } catch (e: Exception) {
            assertEquals("HTTP error code: 502", e.message)
        }
    }

    @Test
    fun translateGemini_trims_text() = runTest {
        val api = apiReturning(HttpStatusCode.OK, geminiTextResponse("  Hola  "))
        assertEquals("Hola", api.translateGemini("Hello", "English", "Spanish"))
    }

    @Test
    fun translateGemini_response_part_missing_text_fails_loudly() = runTest {
        // Regression guard: the hand-written GeminiPartSerializer must enforce the
        // required 'text' field like the @Serializable plugin did — a part object
        // without it must fail the call, not decode to "" and reach the UI as an
        // empty Success card.
        val api = apiReturning(HttpStatusCode.OK, """{"candidates":[{"content":{"parts":[{}]}}]}""")
        try {
            val result = api.translateGemini("Hello", "English", "Spanish")
            fail("expected a deserialization failure, got '$result'")
        } catch (e: Exception) {
            // Ktor may wrap the SerializationException; reaching here is the contract.
        }
    }

    @Test
    fun translateGemini_server_error_includes_status_and_body() = runTest {
        val api = apiReturning(HttpStatusCode.InternalServerError, "boom")
        try {
            api.translateGemini("Hello", "English", "Spanish")
            fail("expected exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("Gemini server returned error: 500 - "))
            assertTrue(e.message!!.contains("boom"))
        }
    }

    @Test
    fun gemini_paths_reject_placeholder_key_before_any_request() = runTest {
        val api = apiReturning(HttpStatusCode.OK, "unused", apiKey = "MY_GEMINI_API_KEY")
        try {
            api.translateGemini("Hello", "English", "Spanish")
            fail("expected exception")
        } catch (e: Exception) {
            assertEquals("Gemini API key is not configured in the Secrets panel.", e.message)
        }
    }

    @Test
    fun validateTranslation_parses_fenced_json_body() = runTest {
        val inner = "```json\n{\"isWarning\":true,\"explanation\":\"careful\",\"suggestions\":[{\"tone\":\"Polite\",\"text\":\"Please help\"}]}\n```"
        val api = apiReturning(HttpStatusCode.OK, geminiTextResponse(inner))
        val result = api.validateTranslation("Hi", "Hola", "English", "Spanish")
        assertTrue(result.isWarning)
        assertEquals("careful", result.explanation)
        assertEquals(1, result.suggestions.size)
        assertEquals("Polite", result.suggestions[0].tone)
        assertEquals("Please help", result.suggestions[0].text)
    }
}
