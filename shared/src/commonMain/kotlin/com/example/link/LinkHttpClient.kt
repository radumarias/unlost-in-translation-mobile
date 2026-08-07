package com.example.link

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.websocket.WebSockets

/**
 * The [HttpClient] the link talks over, built from a platform [engine] — OkHttp on
 * Android, Js on wasm, exactly as `translationHttpClient` is.
 *
 * Separate from the translation client for two reasons. It installs `WebSockets`,
 * which the translation client has no use for; and the translation client is handed
 * a `MockEngine` throughout the test suite, and `MockEngine` does not implement the
 * WebSocket capability at all — sharing one client would make every existing API
 * test carry a plugin it cannot serve.
 *
 * Ktor 3 ships the WebSocket client plugin inside `ktor-client-core` for both `-jvm`
 * and `-wasm-js`, so this costs no new dependency (issue #33, finding #1, confirmed
 * by the Phase 0 spike on both targets).
 */
fun linkHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    install(WebSockets)
}
