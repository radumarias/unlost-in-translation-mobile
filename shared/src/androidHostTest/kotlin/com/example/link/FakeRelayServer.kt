package com.example.link

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * A stand-in for `relay/src/worker.ts`, in Kotlin, on the JVM host.
 *
 * It exists because `MockEngine` does not speak WebSocket, so the only way to prove
 * the transport is against a real socket. It implements the parts of the relay's
 * contract the client actually depends on — fan-out to the *other* member, a
 * per-member replay ring handed to whoever reconnects, and one socket per member id
 * — and nothing else. The real thing is verified separately by `relay/scripts/smoke.mjs`
 * against `wrangler dev`; this is here to exercise the client.
 */
class FakeRelayServer {
    private val mutex = Mutex()
    private val sockets = mutableMapOf<String, DefaultWebSocketServerSession>()
    private val rings = mutableMapOf<String, MutableList<String>>()
    private var server: EmbeddedServer<*, *>? = null

    var port: Int = 0
        private set

    val baseUrl: String get() = "ws://127.0.0.1:$port"

    suspend fun start() {
        val engine = embeddedServer(CIO, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/s/{sid}") { handle(this) }
            }
        }
        engine.start(wait = false)
        port = engine.engine.resolvedConnectors().first().port
        server = engine
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        server = null
    }

    /** Cuts a member's socket the way a lift or a tunnel does. */
    suspend fun drop(member: String) {
        val socket = mutex.withLock { sockets[member] }
        socket?.close(CloseReason(CloseReason.Codes.SERVICE_RESTART, "blip"))
    }

    /** Everything a member has sent, in order — the assertion surface for the tests. */
    suspend fun ringOf(member: String): List<String> = mutex.withLock { rings[member].orEmpty().toList() }

    /**
     * Puts [text] on the wire as if [from] had sent it. Lets a test deliver a frame no
     * [LinkMessage] factory can build — a peer on a protocol this build does not know.
     */
    suspend fun injectFrom(from: String, text: String) {
        val others = mutex.withLock {
            rings.getOrPut(from) { mutableListOf() }.add(text)
            sockets.filterKeys { it != from }.values.toList()
        }
        others.forEach { it.send(Frame.Text(text)) }
    }

    private suspend fun handle(session: DefaultWebSocketServerSession) {
        val member = session.call.request.queryParameters["m"]
        if (member.isNullOrEmpty()) {
            session.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no member id"))
            return
        }
        val replay = mutex.withLock {
            sockets[member] = session
            rings.filterKeys { it != member }.values.flatten()
        }
        replay.forEach { session.send(Frame.Text(it)) }
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                val others = mutex.withLock {
                    rings.getOrPut(member) { mutableListOf() }.add(text)
                    sockets.filterKeys { it != member }.values.toList()
                }
                others.forEach { it.send(Frame.Text(text)) }
            }
        } finally {
            mutex.withLock { if (sockets[member] === session) sockets.remove(member) }
        }
    }
}
