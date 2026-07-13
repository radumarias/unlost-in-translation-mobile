package com.example.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * End-to-end tests for [KtorTranslationApi] using Ktor's MockEngine — no real
 * network. Verifies request/response wiring, the preserved error messages, and
 * the API-key guard (Phase 1 task 4).
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
        val escaped = LenientJson.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(text))
        return """{"candidates":[{"content":{"parts":[{"text":$escaped}]}}]}"""
    }

    @Test
    fun `translateGoogle parses body`() = runTest {
        val api = apiReturning(HttpStatusCode.OK, "[[[\"Hola\",\"Hello\",null,null,10]],null,\"en\"]")
        assertEquals("Hola", api.translateGoogle("Hello", "en", "es"))
    }

    @Test
    fun `translateGoogle url-encodes the query and sets params`() = runTest {
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

        assertTrue(capturedUrl, capturedUrl.contains("client=gtx"))
        assertTrue(capturedUrl, capturedUrl.contains("sl=en"))
        assertTrue(capturedUrl, capturedUrl.contains("tl=es"))
        assertTrue(capturedUrl, capturedUrl.contains("dt=t"))
        // raw '&' / '?' from the text must be percent-encoded, not injected as
        // extra query delimiters
        assertTrue(capturedUrl, capturedUrl.contains("q=a"))
        assertFalse("raw ampersand leaked into query: $capturedUrl", capturedUrl.contains("b?"))
    }

    @Test
    fun `translateGoogle blank text short-circuits`() = runTest {
        val api = apiReturning(HttpStatusCode.OK, "unused")
        assertEquals("", api.translateGoogle("   ", "en", "es"))
    }

    @Test
    fun `translateGoogle non-2xx preserves HTTP error message`() = runTest {
        val api = apiReturning(HttpStatusCode.BadGateway, "nope")
        try {
            api.translateGoogle("Hello", "en", "es")
            fail("expected exception")
        } catch (e: Exception) {
            assertEquals("HTTP error code: 502", e.message)
        }
    }

    @Test
    fun `translateGemini trims text`() = runTest {
        val api = apiReturning(HttpStatusCode.OK, geminiTextResponse("  Hola  "))
        assertEquals("Hola", api.translateGemini("Hello", "English", "Spanish"))
    }

    @Test
    fun `translateGemini server error includes status and body`() = runTest {
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
    fun `gemini paths reject placeholder key before any request`() = runTest {
        val api = apiReturning(HttpStatusCode.OK, "unused", apiKey = "MY_GEMINI_API_KEY")
        try {
            api.translateGemini("Hello", "English", "Spanish")
            fail("expected exception")
        } catch (e: Exception) {
            assertEquals("Gemini API key is not configured in the Secrets panel.", e.message)
        }
    }

    @Test
    fun `validateTranslation parses fenced json body`() = runTest {
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
