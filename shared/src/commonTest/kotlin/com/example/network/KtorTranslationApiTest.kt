package com.example.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import com.example.model.ContextTurn
import com.example.model.TranslationContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    /** A Gemini-returning engine that also records each request's body. */
    private fun apiCapturingBody(bodies: MutableList<String>, text: String = "ok"): KtorTranslationApi {
        val engine = MockEngine { request ->
            bodies += request.body.toByteArray().decodeToString()
            respond(
                content = geminiTextResponse(text),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(LenientJson) }
            install(HttpTimeout)
        }
        return KtorTranslationApi(apiKey = "test-key", client = client)
    }

    // --- Tone / situation prompt constraints (ported from the web build) ---

    @Test
    fun translateGemini_default_context_sends_the_original_prompt() = runTest {
        val bodies = mutableListOf<String>()
        apiCapturingBody(bodies).translateGemini("Hello", "English", "Spanish")

        val body = bodies.single()
        assertFalse(body.contains("IMPORTANT:"), body)
        // The unconstrained prompt is byte-identical to what it was before
        // tone/situation existed — same single line, same single space.
        assertTrue(body.contains("extra words. Text to translate:"), body)
    }

    @Test
    fun translateGemini_sends_tone_and_situation_constraints() = runTest {
        val bodies = mutableListOf<String>()
        apiCapturingBody(bodies).translateGemini(
            "Hello", "English", "Spanish",
            TranslationContext(tone = "Formal", situation = "Medical"),
        )

        val body = bodies.single()
        assertTrue(body.contains("The requested conversation tone is \\\"Formal\\\""), body)
        assertTrue(body.contains("The requested situation/context is \\\"Medical\\\""), body)
        assertTrue(body.contains("STRICTLY prioritize vocabulary and politeness"), body)
    }

    @Test
    fun translateGemini_omits_the_tone_line_when_only_a_situation_is_set() = runTest {
        val bodies = mutableListOf<String>()
        apiCapturingBody(bodies).translateGemini(
            "Hello", "English", "Spanish",
            TranslationContext(situation = "Emergency"),
        )

        val body = bodies.single()
        assertFalse(body.contains("conversation tone"), body)
        assertTrue(body.contains("The requested situation/context is \\\"Emergency\\\""), body)
    }

    @Test
    fun translateGemini_renders_prior_turns_as_conversation_history() = runTest {
        val bodies = mutableListOf<String>()
        apiCapturingBody(bodies).translateGemini(
            "and for me?", "English", "Spanish",
            TranslationContext(
                history = listOf(
                    ContextTurn("English", "we would like the fish"),
                    ContextTurn("Spanish", "claro, para cuantas personas?"),
                ),
            ),
        )

        val body = bodies.single()
        assertTrue(body.contains("Here is the conversation history so far:"), body)
        assertTrue(body.contains("English Speaker: we would like the fish"), body)
        assertTrue(body.contains("Spanish Speaker: claro, para cuantas personas?"), body)
        // History precedes the text being translated.
        assertTrue(body.indexOf("English Speaker") < body.indexOf("Text to translate"), body)
    }

    @Test
    fun translateGemini_puts_history_before_the_tone_constraints() = runTest {
        val bodies = mutableListOf<String>()
        apiCapturingBody(bodies).translateGemini(
            "sure", "English", "Spanish",
            TranslationContext(tone = "Formal", history = listOf(ContextTurn("Spanish", "hola"))),
        )

        val body = bodies.single()
        assertTrue(body.indexOf("Spanish Speaker: hola") < body.indexOf("IMPORTANT:"), body)
    }

    @Test
    fun validateTranslation_carries_the_same_constraints() = runTest {
        val bodies = mutableListOf<String>()
        val api = apiCapturingBody(bodies, text = """{"isWarning":false,"explanation":"fine"}""")

        api.validateTranslation(
            "Hello", "Hola", "English", "Spanish",
            TranslationContext(tone = "Playful", situation = "Dating"),
        )

        val body = bodies.single()
        assertTrue(body.contains("The requested conversation tone is \\\"Playful\\\""), body)
        assertTrue(body.contains("The requested situation/context is \\\"Dating\\\""), body)
        // The constraints sit between the analysis header and the JSON schema.
        assertTrue(
            body.indexOf("Translated Text") < body.indexOf("IMPORTANT:") &&
                body.indexOf("IMPORTANT:") < body.indexOf("Return ONLY a valid JSON object"),
            body,
        )
    }

    @Test
    fun validateTranslation_asks_for_the_sanity_check_and_idiom_keys() = runTest {
        val bodies = mutableListOf<String>()
        val api = apiCapturingBody(
            bodies,
            text = """{"isWarning":false,"explanation":"ok","sanityCheck":"They hear a polite request.","idiomExplanation":null}""",
        )

        val result = api.validateTranslation("Hello", "Hola", "English", "Spanish")

        val body = bodies.single()
        assertTrue(body.contains("sanityCheck"), body)
        assertTrue(body.contains("idiomExplanation"), body)
        assertEquals("They hear a polite request.", result.sanityCheck)
        assertNull(result.idiomExplanation)
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
    fun gemini_sends_the_key_in_a_header_and_never_in_the_url() = runTest {
        // Security regression guard. Ktor embeds the full request URL in its
        // timeout exception message ("Request timeout has expired [url=...]"),
        // and the view model renders that message on screen and writes it to
        // logcat/console — so a key in the query string leaks on every timeout.
        // Verified empirically before this fix: the key appeared verbatim in the
        // exception message. Keep it in the header.
        var capturedUrl = ""
        var capturedHeader: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            capturedHeader = request.headers["x-goog-api-key"]
            respond(
                content = geminiTextResponse("Hola"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(LenientJson) }
            install(HttpTimeout)
        }
        val api = KtorTranslationApi(apiKey = "SECRET-KEY-VALUE", client = client)

        api.translateGemini("Hello", "English", "Spanish")

        assertEquals("SECRET-KEY-VALUE", capturedHeader, "the key must travel in the header")
        assertFalse(capturedUrl.contains("SECRET-KEY-VALUE"), "the key must never appear in the URL: $capturedUrl")
        assertFalse(capturedUrl.contains("key="), "no key query parameter: $capturedUrl")
    }

    @Test
    fun geminiTimeout_message_does_not_contain_the_key() = runTest {
        // The end-to-end property the header change exists to guarantee.
        val engine = MockEngine { request -> throw HttpRequestTimeoutException(request) }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(LenientJson) }
            install(HttpTimeout)
        }
        val api = KtorTranslationApi(apiKey = "SECRET-KEY-VALUE", client = client)
        try {
            api.translateGemini("Hello", "English", "Spanish")
            fail("expected a timeout exception")
        } catch (e: Exception) {
            assertFalse(
                e.message!!.contains("SECRET-KEY-VALUE"),
                "the timeout message must not carry the key: ${e.message}",
            )
        }
    }

    /**
     * Gemini client backed by a scripted sequence of responses, one per attempt
     * (the last entry repeats), counting how many attempts the API made and
     * which URLs it hit — model-blind: the same script answers whichever model
     * the API tries.
     */
    private class SequencedGemini(
        private val statuses: List<HttpStatusCode>,
        private val bodies: List<String>,
    ) {
        var attempts = 0
            private set
        val urls = mutableListOf<String>()
        val api: KtorTranslationApi

        init {
            val engine = MockEngine { request ->
                val i = attempts
                attempts++
                urls.add(request.url.toString())
                respond(
                    content = bodies[minOf(i, bodies.lastIndex)],
                    status = statuses[minOf(i, statuses.lastIndex)],
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val client = HttpClient(engine) {
                expectSuccess = false
                install(ContentNegotiation) { json(LenientJson) }
                install(HttpTimeout)
            }
            api = KtorTranslationApi(apiKey = "test-key", client = client)
        }
    }

    /**
     * Gemini client whose scripted responses differ per model (issue #3
     * fallback tests): one script for gemini-3.5-flash, one for the
     * gemini-3-flash-preview fallback, each one entry per attempt with the
     * last repeating. A null entry makes the engine throw like the wasm Js
     * engine does when the network fails.
     */
    private class ModelRoutedGemini(
        private val primary: List<Pair<HttpStatusCode, String>?>,
        private val fallback: List<Pair<HttpStatusCode, String>?>,
    ) {
        var primaryAttempts = 0
            private set
        var fallbackAttempts = 0
            private set
        val api: KtorTranslationApi

        init {
            val engine = MockEngine { request ->
                val isFallback = request.url.toString().contains("gemini-3-flash")
                val script = if (isFallback) fallback else primary
                val i = if (isFallback) fallbackAttempts++ else primaryAttempts++
                val step = script[minOf(i, script.lastIndex)] ?: throw Error("Fail to fetch")
                respond(
                    content = step.second,
                    status = step.first,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val client = HttpClient(engine) {
                expectSuccess = false
                install(ContentNegotiation) { json(LenientJson) }
                install(HttpTimeout)
            }
            api = KtorTranslationApi(apiKey = "test-key", client = client)
        }
    }

    /** A 429 body for a short burst limit — worth retrying. */
    private fun burstLimitBody(): String =
        """{"error":{"code":429,"message":"Too many requests","status":"UNAVAILABLE"}}"""

    /**
     * A 429 body for an exhausted daily allowance, copied from the shape the
     * live free-tier API returns. Not worth retrying — the quota does not come
     * back for hours.
     */
    private fun quotaExhaustedBody(): String =
        """{"error":{"code":429,"message":"You exceeded your current quota. Please retry in 8.9s.","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaValue":"20"}]}]}}"""

    @Test
    fun gemini_retries_a_burst_rate_limit_then_succeeds() = runTest {
        val seq = SequencedGemini(
            statuses = listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.OK),
            bodies = listOf(burstLimitBody(), geminiTextResponse("Hola")),
        )
        assertEquals("Hola", seq.api.translateGemini("Hello", "English", "Spanish"))
        assertEquals(2, seq.attempts, "expected one retry after the burst 429")
    }

    @Test
    fun gemini_never_retries_an_exhausted_quota_on_either_model() = runTest {
        // Measured against the live API: retrying an exhausted free-tier quota
        // only spends more of an allowance that is already gone (it tripled the
        // request volume and made every call fail). The daily quota is counted
        // per model, so the fallback model is still worth ONE try — exactly one
        // request per model, no retries on either.
        val seq = SequencedGemini(
            statuses = listOf(HttpStatusCode.TooManyRequests),
            bodies = listOf(quotaExhaustedBody()),
        )
        try {
            seq.api.translateGemini("Hello", "English", "Spanish")
            fail("expected exception")
        } catch (e: Exception) {
            assertEquals(KtorTranslationApi.GEMINI_QUOTA_MESSAGE, e.message)
        }
        assertEquals(2, seq.attempts, "one attempt per model, never more")
        assertTrue(seq.urls[0].contains("gemini-3.5-flash"), seq.urls[0])
        assertTrue(seq.urls[1].contains("gemini-3-flash"), seq.urls[1])
    }

    @Test
    fun gemini_retries_server_error_then_succeeds() = runTest {
        val seq = SequencedGemini(
            statuses = listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.OK),
            bodies = listOf("upstream down", geminiTextResponse("Hola")),
        )
        assertEquals("Hola", seq.api.translateGemini("Hello", "English", "Spanish"))
        assertEquals(2, seq.attempts, "expected one retry after the 503")
    }

    @Test
    fun gemini_gives_up_after_both_models_exhaust_their_attempt_caps() = runTest {
        // A sustained 5xx on every model: 3 attempts on the primary, fall back,
        // 3 on the fallback, then surface the last honest error.
        val seq = SequencedGemini(
            statuses = listOf(HttpStatusCode.ServiceUnavailable),
            bodies = listOf("upstream down"),
        )
        try {
            seq.api.translateGemini("Hello", "English", "Spanish")
            fail("expected exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("503"), "message was: ${e.message}")
        }
        assertEquals(6, seq.attempts, "expected the 3-attempt cap on each of the two models")
    }

    @Test
    fun gemini_does_not_retry_or_fall_back_on_client_errors() = runTest {
        // A 400 fails identically on a retry AND on the sibling model —
        // surface it at once, spend nothing.
        val seq = SequencedGemini(
            statuses = listOf(HttpStatusCode.BadRequest),
            bodies = listOf("bad request"),
        )
        try {
            seq.api.translateGemini("Hello", "English", "Spanish")
            fail("expected exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("400"), "message was: ${e.message}")
        }
        assertEquals(1, seq.attempts, "client errors must not be retried or fall back")
        assertTrue(seq.urls.single().contains("gemini-3.5-flash"), "must never reach the fallback model")
    }

    @Test
    fun validateTranslation_surfaces_the_quota_message_verbatim() = runTest {
        // The view model prints this message as-is (no "Validation failed:"
        // prefix), so it must arrive intact.
        val seq = SequencedGemini(
            statuses = listOf(HttpStatusCode.TooManyRequests),
            bodies = listOf(quotaExhaustedBody()),
        )
        try {
            seq.api.validateTranslation("Hello", "Hola", "English", "Spanish")
            fail("expected exception")
        } catch (e: Exception) {
            assertEquals(KtorTranslationApi.GEMINI_QUOTA_MESSAGE, e.message)
        }
        assertEquals(2, seq.attempts, "one attempt per model, never more")
    }

    @Test
    fun validateTranslation_retries_a_burst_rate_limit_then_succeeds() = runTest {
        val seq = SequencedGemini(
            statuses = listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.OK),
            bodies = listOf(
                burstLimitBody(),
                geminiTextResponse("""{"isWarning":false,"explanation":"fine","suggestions":[]}"""),
            ),
        )
        val result = seq.api.validateTranslation("Hello", "Hola", "English", "Spanish")
        assertFalse(result.isWarning)
        assertEquals(2, seq.attempts, "expected one retry after the burst 429")
    }

    @Test
    fun translateGemini_empty_candidates_gives_a_clear_message() = runTest {
        // A 200 with candidates=[] (whole response blocked by a safety filter)
        // must read as an explanation, not "Collection is empty".
        val api = apiReturning(HttpStatusCode.OK, """{"candidates":[]}""")
        try {
            api.translateGemini("Hello", "English", "Spanish")
            fail("expected exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("no result", ignoreCase = true), "message was: ${e.message}")
            assertFalse(e.message!!.contains("Collection is empty"), "leaked the raw error: ${e.message}")
        }
    }

    @Test
    fun translateGemini_empty_parts_gives_a_clear_message() = runTest {
        // A candidate cut off with an empty parts array (finishReason
        // SAFETY/MAX_TOKENS) must also read as an explanation.
        val api = apiReturning(HttpStatusCode.OK, """{"candidates":[{"content":{"parts":[]}}]}""")
        try {
            api.translateGemini("Hello", "English", "Spanish")
            fail("expected exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("empty result", ignoreCase = true), "message was: ${e.message}")
        }
    }

    @Test
    fun validateTranslation_empty_candidates_gives_a_clear_message() = runTest {
        val api = apiReturning(HttpStatusCode.OK, """{"candidates":[]}""")
        try {
            api.validateTranslation("Hello", "Hola", "English", "Spanish")
            fail("expected exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("no result", ignoreCase = true), "message was: ${e.message}")
        }
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
        // A body that isn't Google's error JSON falls back to the raw text.
        val api = apiReturning(HttpStatusCode.InternalServerError, "boom")
        try {
            api.translateGemini("Hello", "English", "Spanish")
            fail("expected exception")
        } catch (e: Exception) {
            assertEquals("Gemini error (500): boom", e.message)
        }
    }

    // --- Issue #3: model fallback + honest error copy ---

    /** The 503 body observed live on 2026-07-13 — the incident behind issue #3. */
    private fun highDemand503Body(): String =
        """{"error":{"code":503,"message":"This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later.","status":"UNAVAILABLE"}}"""

    @Test
    fun gemini_falls_back_when_the_primary_model_stays_unavailable() = runTest {
        // The issue #3 incident: a sustained gemini-3.5-flash 503 spike with
        // gemini-3-flash healthy must end in a normal result, not an error.
        val routed = ModelRoutedGemini(
            primary = listOf(HttpStatusCode.ServiceUnavailable to highDemand503Body()),
            fallback = listOf(HttpStatusCode.OK to geminiTextResponse("Hola")),
        )
        assertEquals("Hola", routed.api.translateGemini("Hello", "English", "Spanish"))
        assertEquals(3, routed.primaryAttempts, "primary gets its full attempt cap first")
        assertEquals(1, routed.fallbackAttempts, "fallback succeeds on its first attempt")
    }

    @Test
    fun validateTranslation_succeeds_via_the_fallback_model() = runTest {
        val routed = ModelRoutedGemini(
            primary = listOf(HttpStatusCode.ServiceUnavailable to highDemand503Body()),
            fallback = listOf(
                HttpStatusCode.OK to
                    geminiTextResponse("""{"isWarning":false,"explanation":"fine","suggestions":[]}"""),
            ),
        )
        val result = routed.api.validateTranslation("Hello", "Hola", "English", "Spanish")
        assertFalse(result.isWarning)
        assertEquals(1, routed.fallbackAttempts)
    }

    @Test
    fun gemini_exhausted_quota_advances_straight_to_the_fallback_model() = runTest {
        // The free tier's daily allowance is per model: when the primary's is
        // spent, the fallback's is untouched — advance immediately, retry nothing.
        val routed = ModelRoutedGemini(
            primary = listOf(HttpStatusCode.TooManyRequests to quotaExhaustedBody()),
            fallback = listOf(HttpStatusCode.OK to geminiTextResponse("Hola")),
        )
        assertEquals("Hola", routed.api.translateGemini("Hello", "English", "Spanish"))
        assertEquals(1, routed.primaryAttempts, "an exhausted quota must not be retried")
        assertEquals(1, routed.fallbackAttempts)
    }

    @Test
    fun gemini_unknown_model_404_skips_retries_and_falls_back() = runTest {
        // A 404 means the model id itself is unknown — retrying is pointless,
        // but the sibling model may exist (issue #3).
        val routed = ModelRoutedGemini(
            primary = listOf(
                HttpStatusCode.NotFound to
                    """{"error":{"code":404,"message":"Model not found","status":"NOT_FOUND"}}""",
            ),
            fallback = listOf(HttpStatusCode.OK to geminiTextResponse("Hola")),
        )
        assertEquals("Hola", routed.api.translateGemini("Hello", "English", "Spanish"))
        assertEquals(1, routed.primaryAttempts, "a 404 must not be retried")
        assertEquals(1, routed.fallbackAttempts)
    }

    @Test
    fun gemini_network_failures_retry_then_fall_back() = runTest {
        // Engine-thrown failures (wasm's kotlin.Error, IOException, timeouts)
        // are transient too: retry them, and if the primary never answers,
        // still give the fallback its chance.
        val routed = ModelRoutedGemini(
            primary = listOf(null),
            fallback = listOf(HttpStatusCode.OK to geminiTextResponse("Hola")),
        )
        assertEquals("Hola", routed.api.translateGemini("Hello", "English", "Spanish"))
        assertEquals(3, routed.primaryAttempts, "network failures get the full attempt cap")
        assertEquals(1, routed.fallbackAttempts)
    }

    @Test
    fun gemini_error_copy_carries_googles_own_words() = runTest {
        // Issue #3's third defect: the on-screen error was a bare status code.
        // It must now carry Google's human-readable error.message.
        val seq = SequencedGemini(
            statuses = listOf(HttpStatusCode.ServiceUnavailable),
            bodies = listOf(highDemand503Body()),
        )
        try {
            seq.api.translateGemini("Hello", "English", "Spanish")
            fail("expected exception")
        } catch (e: Exception) {
            assertEquals(
                "Gemini unavailable (503): This model is currently experiencing high demand. " +
                    "Spikes in demand are usually temporary. Please try again later.",
                e.message,
            )
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
