package com.example.link

import com.example.platform.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [ConversationLink] over the Cloudflare relay in `relay/` (issue #33 Phase 2).
 *
 * Entirely common code — there is no `expect`/`actual` anywhere in the transport,
 * because Ktor's WebSocket client works on both targets. The only per-platform piece
 * in the whole feature is the QR *scanner*.
 *
 * @param client from [linkHttpClient] — must have the `WebSockets` plugin installed.
 * @param relayBaseUrl a `wss://` origin, or blank when the build has no relay
 *   configured, in which case connecting fails with [LinkFailure.NOT_CONFIGURED]
 *   rather than pretending.
 * @param scope owns the socket coroutine; the container passes an application-scoped
 *   one so a session survives the view model being recreated on rotation.
 * @param newMemberId injectable only so tests can pin the id; production uses the CSPRNG.
 * @param maxAttempts consecutive failed connections before the session gives up.
 * @param retryBaseDelayMs first backoff step; it doubles per attempt.
 */
class WebSocketConversationLink(
    private val client: HttpClient,
    private val relayBaseUrl: String,
    private val scope: CoroutineScope,
    private val log: AppLog? = null,
    private val newMemberId: () -> String = SessionTicket::newMemberId,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val retryBaseDelayMs: Long = RETRY_BASE_DELAY_MS,
) : ConversationLink {

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<LinkMessage>(extraBufferCapacity = INBOX_CAPACITY)
    override val inbound: Flow<LinkMessage> = _inbound.asSharedFlow()

    override var memberId: String = ""
        private set

    /**
     * Outgoing frames wait here rather than going straight to the socket, so a turn
     * spoken during a blip is delivered on reconnect instead of thrown away. Closing
     * it is also how [disconnect] asks the session to shut down cleanly: the writer
     * drains what is left — the `bye` included — and only then closes the socket.
     *
     * Recreated per session. A queue that outlived its session would deliver the
     * previous conversation's last turn into the next one.
     */
    private var outbox: Channel<LinkMessage>? = null
    private var sessionJob: Job? = null

    override suspend fun connect(ticket: SessionTicket, myLang: String, myName: String?) {
        disconnect()
        if (relayBaseUrl.isBlank()) {
            _state.value = LinkState.Failed(LinkFailure.NOT_CONFIGURED)
            return
        }
        memberId = newMemberId()
        val queue = Channel<LinkMessage>(capacity = OUTBOX_CAPACITY)
        outbox = queue
        _state.value = LinkState.Connecting
        sessionJob = scope.launch { runSession(ticket, myLang, myName, queue) }
    }

    /**
     * Queues [message]. Silently does nothing when there is no session — the view
     * model can race a peer's `bye` against the user's own turn, and neither an
     * exception nor an error banner is the right answer to "you spoke as it ended".
     */
    override suspend fun send(message: LinkMessage) {
        // Stamp `from` here rather than trusting the caller: the id belongs to the
        // link, and a frame carrying someone else's would be taken for the peer's.
        val queue = outbox ?: return
        runCatching { queue.send(message.copy(from = memberId)) }
    }

    override suspend fun disconnect() {
        val job = sessionJob ?: return
        val queue = outbox
        sessionJob = null
        outbox = null
        if (queue != null) {
            // Best effort. If the socket is already gone the send never drains, so it
            // is bounded; the peer also learns from the relay closing its side, and
            // the session TTL cleans up regardless.
            runCatching { queue.send(LinkMessage.bye(memberId)) }
            queue.close()
            withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { job.join() }
        }
        job.cancel()
        _state.value = LinkState.Idle
    }

    private suspend fun runSession(
        ticket: SessionTicket,
        myLang: String,
        myName: String?,
        queue: Channel<LinkMessage>,
    ) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                client.webSocket(sessionUrl(ticket.sessionId, memberId)) {
                    attempt = 0
                    _state.value = LinkState.Connected()
                    // Re-announced on every reconnect, not only the first: after a drop
                    // the peer may have restarted and no longer know our language.
                    outgoing.send(Frame.Text(LinkCodec.encode(LinkMessage.hello(memberId, myLang, myName))))
                    val writer = launch {
                        // Ends when disconnect() closes the queue, having drained the
                        // `bye` it queued first.
                        for (message in queue) {
                            outgoing.send(Frame.Text(LinkCodec.encode(message)))
                        }
                        close(CloseReason(CloseReason.Codes.NORMAL, "bye"))
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) onFrame(frame.readText())
                        }
                    } finally {
                        writer.cancel()
                    }
                    val code = closeReason.await()?.code?.toInt()
                    if (code != null) log?.d(TAG, "relay closed the session with code $code")
                    if (code != null && code in PROTOCOL_CLOSE_CODES) {
                        _state.value = LinkState.Failed(LinkFailure.PROTOCOL)
                    }
                }
            } catch (t: Throwable) {
                // Throwable, not Exception: the wasm JS engine surfaces a failed fetch
                // as `kotlin.Error` — the same trap `KtorTranslationApi.apiCall`
                // documents. Cancellation still has to propagate.
                if (t is CancellationException) throw t
                log?.d(TAG, "session attempt ${attempt + 1} failed: ${t.message}")
            }
            // A closed queue means we are shutting down on purpose, and a terminal
            // state means reconnecting would only reproduce it.
            if (queue.isClosedForSend || _state.value is LinkState.Failed) return
            attempt += 1
            if (attempt >= maxAttempts) {
                _state.value = LinkState.Failed(LinkFailure.NETWORK)
                return
            }
            _state.value = LinkState.Connecting
            delay(retryBaseDelayMs shl (attempt - 1))
        }
    }

    private suspend fun onFrame(text: String) {
        val message = LinkCodec.decode(text)
        if (message == null) {
            // One bad frame costs one frame, never the session. A peer on a newer
            // protocol is the likeliest cause, and tearing down over it is worse.
            log?.d(TAG, "dropped an unparseable frame (${text.length} chars)")
            return
        }
        if (message.from == memberId) return // the relay never does this; belt and braces
        when (message.type) {
            LinkMessage.TYPE_HELLO -> _state.value = LinkState.Connected(
                peerPresent = true,
                peerLang = message.lang,
                peerName = message.name,
            )
            LinkMessage.TYPE_BYE -> _state.value = LinkState.Failed(LinkFailure.PEER_LEFT)
            else -> {
                val current = _state.value
                if (current is LinkState.Connected && !current.peerPresent) {
                    _state.value = current.copy(peerPresent = true)
                }
            }
        }
        _inbound.emit(message)
    }

    /** `wss://<relay>/s/<sid>?m=<memberId>` — the QR secret appears in neither. */
    private fun sessionUrl(sessionId: String, member: String): String =
        "${relayBaseUrl.trimEnd('/')}/s/$sessionId?m=$member"

    private companion object {
        const val TAG = "ConversationLink"
        const val INBOX_CAPACITY = 64
        const val OUTBOX_CAPACITY = 64
        const val MAX_ATTEMPTS = 5
        const val RETRY_BASE_DELAY_MS = 500L
        const val SHUTDOWN_TIMEOUT_MS = 1000L

        /**
         * The relay's own rejections: 1003 non-text frame, 1008 rate cap, 1009 frame
         * too large. All three mean this client did something the protocol forbids, so
         * reconnecting would only do it again.
         */
        val PROTOCOL_CLOSE_CODES = setOf(1003, 1008, 1009)
    }
}
