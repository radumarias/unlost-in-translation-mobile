package com.example.link

import com.example.link.crypto.LinkCryptoException
import com.example.link.crypto.LinkRole
import com.example.link.crypto.SessionCrypto
import com.example.link.crypto.SessionKeyExchange
import com.example.link.crypto.SessionKeySchedule
import com.example.platform.AppLog
import dev.whyoleg.cryptography.random.CryptographyRandom
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
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
import kotlin.time.TimeSource

/**
 * [ConversationLink] over the Cloudflare relay in `relay/`, end-to-end encrypted
 * (issues #33 Phase 2 and #34 §3).
 *
 * Entirely common code — there is no `expect`/`actual` anywhere in the transport or
 * its cryptography, because Ktor's WebSocket client and cryptography-kotlin both
 * work on either target. The only per-platform piece in the whole feature is the QR
 * *scanner*.
 *
 * **It fails closed.** A bad confirmation MAC, a GCM tag failure, a malformed or
 * off-curve public key, a host key that does not match the one the QR promised, or
 * a ticket with no secret all end the session. There is no plaintext fallback and no
 * partial-trust state: a peer that cannot complete the handshake is a peer this
 * build refuses to talk to. Nothing reaches [inbound] before confirmation succeeds.
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
 * @param stableConnectionMs how long a connection must last to count as progress and
 *   clear the attempt counter. Injectable so tests need not sit through it.
 */
class WebSocketConversationLink(
    private val client: HttpClient,
    private val relayBaseUrl: String,
    private val scope: CoroutineScope,
    private val log: AppLog? = null,
    private val newMemberId: () -> String = SessionTicket::newMemberId,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val retryBaseDelayMs: Long = RETRY_BASE_DELAY_MS,
    private val stableConnectionMs: Long = STABLE_CONNECTION_MS,
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
     */
    private var outbox: Channel<LinkMessage>? = null
    private var sessionJob: Job? = null

    /**
     * The last few frames written, re-sent at the top of every new connection.
     *
     * Taking a message off the queue and writing it are two steps, and a socket can
     * die between them — or accept the write into a connection the far end has
     * already closed, which looks like success and is not. Either way the frame is
     * gone, and it is precisely the frame the user spoke as the signal dropped.
     *
     * Re-sent frames are sealed afresh under new counters, so this never reuses a
     * nonce; the duplicate is caught by the turn id at the far end.
     */
    private val unconfirmed = ArrayDeque<LinkMessage>()

    /**
     * What the peer has told us about itself, kept outside [LinkState] because the
     * state is rebuilt on every reconnect and this outlives one connection. The
     * relay reports no presence of its own, so once a peer has been heard from it
     * is treated as present until it says `bye`.
     */
    private var peerPresent = false
    private var peerLang: String? = null
    private var peerName: String? = null

    // --- issue #34 session state. All of it lives in RAM for one session and is
    // never written anywhere: if the process dies the session is over, which is what
    // keeps this feature free of key storage and of every question storage brings. ---

    /** This device's ephemeral key pair, generated per session. */
    private var exchange: SessionKeyExchange? = null

    /** The hybrid secret: the QR's 256 bits, or the manual code's bytes. */
    private var sessionSecret: ByteArray? = null

    /** Non-null once the handshake has completed; survives reconnects (§4). */
    private var crypto: SessionCrypto? = null

    private var role: LinkRole = LinkRole.GUEST
    private var verification: Verification = Verification.NONE

    /** Set by [createHostTicket] so the following [connect] can reuse the same key pair. */
    private var pendingHostTicket: SessionTicket? = null

    override suspend fun createHostTicket(hostLang: String?, useManualCode: Boolean): SessionTicket {
        val keys = SessionKeyExchange.generate()
        val ticket = if (useManualCode) {
            // The degraded path (§5): the six characters *are* the secret, and they
            // carry ~30 bits. The host's public key cannot be in a typed code, so it
            // is exchanged over the relay and the SAS is what authenticates it.
            SessionTicket.forManualCode(SessionTicket.newManualCode(), hostLang)
                ?: error("a freshly generated manual code must parse")
        } else {
            SessionTicket(
                sessionId = SessionTicket.newSessionId(),
                hostLang = hostLang,
                qrSecret = Base64Url.encode(CryptographyRandom.nextBytes(QR_SECRET_BYTES)),
                hostPublicKey = Base64Url.encode(keys.publicKey),
            )
        }
        exchange = keys
        pendingHostTicket = ticket
        return ticket
    }

    override suspend fun connect(
        ticket: SessionTicket,
        myLang: String,
        myName: String?,
        role: LinkRole,
    ) {
        disconnect()
        if (relayBaseUrl.isBlank()) {
            _state.value = LinkState.Failed(LinkFailure.NOT_CONFIGURED)
            return
        }
        val secret = secretFor(ticket)
        if (secret == null) {
            // §3.7: a ticket that must carry a secret and does not is not something
            // to fall back from. It is a session that never starts.
            _state.value = LinkState.Failed(LinkFailure.ENCRYPTION_FAILED)
            return
        }

        this.role = role
        // The host's key pair was minted by createHostTicket, because its public half
        // is already inside the ticket being published. A guest mints one here.
        exchange = if (role == LinkRole.HOST && pendingHostTicket?.sessionId == ticket.sessionId) {
            exchange
        } else {
            null
        } ?: SessionKeyExchange.generate()
        pendingHostTicket = null
        sessionSecret = secret
        crypto = null
        verification = if (ticket.hostPublicKey != null) {
            Verification.VERIFIED_BY_QR
        } else {
            Verification.NEEDS_SAS_CHECK
        }

        memberId = newMemberId()
        unconfirmed.clear()
        peerPresent = false
        peerLang = null
        peerName = null
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
        val queue = outbox ?: return
        runCatching { queue.send(message.copy(from = memberId)) }
    }

    override suspend fun disconnect() {
        val job = sessionJob ?: return
        val queue = outbox
        sessionJob = null
        outbox = null
        if (queue != null) {
            runCatching { queue.send(LinkMessage.bye(memberId)) }
            queue.close()
            withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { job.join() }
        }
        job.cancel()
        wipeKeys()
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
            val openedAt = TimeSource.Monotonic.markNow()
            try {
                client.webSocket(sessionUrl(ticket.sessionId, memberId)) {
                    // §4: keys and counters survive a reconnect and the handshake is
                    // NOT redone. Only a genuinely new session runs it.
                    if (crypto == null) handshake(ticket)

                    // Reconnecting does not start the conversation over: what the peer
                    // told us about itself is still true, and rebuilding the state from
                    // defaults here would show "waiting for the other phone" to someone
                    // whose peer never left — and its replayed `hello` is refused as a
                    // replay, so there is no second chance to learn it.
                    _state.value = LinkState.Connected(
                        peerPresent = peerPresent,
                        peerLang = peerLang,
                        peerName = peerName,
                        verification = verification,
                        sas = crypto?.sas,
                    )
                    // Re-announced on every reconnect, not only the first: after a
                    // drop the peer may no longer know our language. Encrypted, so
                    // the relay does not learn which languages are being spoken.
                    sendSealed(LinkMessage.hello(memberId, myLang, myName))

                    val writer = launch {
                        for (message in unconfirmed.toList()) sendSealed(message)
                        for (message in queue) {
                            remember(message)
                            sendSealed(message)
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
            } catch (e: LinkCryptoException) {
                // §3.7, fail closed: wipe the keys and stop. No plaintext fallback,
                // and no retry — reconnecting would only reproduce it.
                log?.e(TAG, "the session failed to authenticate (${e.reason})", e)
                wipeKeys()
                _state.value = LinkState.Failed(LinkFailure.ENCRYPTION_FAILED)
                return
            } catch (t: Throwable) {
                // Throwable, not Exception: the wasm JS engine surfaces a failed fetch
                // as `kotlin.Error` — the same trap `KtorTranslationApi.apiCall`
                // documents. Cancellation still has to propagate.
                if (t is CancellationException) throw t
                log?.d(TAG, "session attempt ${attempt + 1} failed: ${t.message}")
            }
            if (queue.isClosedForSend || _state.value is LinkState.Failed) return
            // A socket that opened and closed again immediately is not progress. An
            // expired session does exactly that — the relay accepts the upgrade and
            // hangs up — and resetting the counter on "the upgrade succeeded" would
            // mean `maxAttempts` is never reached and the phone retries for ever.
            // Only a connection that stayed up counts as a fresh start.
            if (openedAt.elapsedNow().inWholeMilliseconds >= stableConnectionMs) attempt = 0
            attempt += 1
            if (attempt >= maxAttempts) {
                _state.value = LinkState.Failed(LinkFailure.NETWORK)
                return
            }
            _state.value = LinkState.Connecting
            delay(retryBaseDelayMs shl (attempt - 1))
        }
    }

    /**
     * Issue #34 §3.3–§3.5, run inline before any application frame is sent or
     * accepted.
     *
     * Both sides always send `kex`. The guest already holds the host's key from the
     * QR, and checks the one that arrives against it — a mismatch is an attack, not
     * a discrepancy to reconcile. On the manual-code path there is no QR key, so
     * `kex` is the only way each side learns the other's, which is exactly why that
     * path needs the SAS.
     */
    private suspend fun DefaultClientWebSocketSession.handshake(ticket: SessionTicket) {
        val keys = exchange ?: throw LinkCryptoException(
            LinkCryptoException.Reason.MISSING_SECRET,
            "no ephemeral key pair for this session",
        )
        _state.value = LinkState.KeyExchange
        outgoing.send(Frame.Text(LinkCodec.encode(LinkMessage.kex(memberId, Base64Url.encode(keys.publicKey)))))

        // Only the guest has an expectation to check: the QR carried the *host's*
        // key, so it is the host's `kex` that must match it. The host holds the same
        // field in its own ticket — that is its own key, and comparing the guest's
        // against it would reject every genuine session.
        val expectedHostKey =
            if (role == LinkRole.GUEST) ticket.hostPublicKey?.let(Base64Url::decodeOrNull) else null
        var peerPublicKey: ByteArray? = expectedHostKey
        var pendingPeerMac: ByteArray? = null
        var sentConfirmation = false

        // Derive as soon as both keys are known — which for a guest on the QR path is
        // immediately, since the QR carried the host's.
        suspend fun deriveIfReady() {
            if (crypto != null) return
            val peer = peerPublicKey ?: return
            val schedule = SessionKeySchedule.derive(
                qrSecret = sessionSecret ?: return,
                sharedSecret = keys.agree(peer),
                sessionId = Base64Url.decodeOrNull(ticket.sessionId) ?: return,
                hostPublicKey = if (role == LinkRole.HOST) keys.publicKey else peer,
                guestPublicKey = if (role == LinkRole.HOST) peer else keys.publicKey,
            )
            crypto = SessionCrypto(schedule, role)
        }

        deriveIfReady()
        if (crypto != null) {
            _state.value = LinkState.Confirming
            outgoing.send(sealedConfirmation())
            sentConfirmation = true
        }

        while (crypto == null || pendingPeerMac == null) {
            val frame = incoming.receive()
            if (frame !is Frame.Text) continue
            val message = LinkCodec.decode(frame.readText()) ?: continue
            if (message.from == memberId) continue

            when (message.type) {
                LinkMessage.TYPE_KEX -> {
                    val received = Base64Url.decodeOrNull(message.pub.orEmpty())
                        ?: throw LinkCryptoException(
                            LinkCryptoException.Reason.BAD_PUBLIC_KEY,
                            "the peer's kex carried a key that is not base64url",
                        )
                    // The QR authenticated the host's key. A different one on the
                    // wire means somebody is standing in the middle.
                    if (expectedHostKey != null && !received.contentEquals(expectedHostKey)) {
                        throw LinkCryptoException(
                            LinkCryptoException.Reason.BAD_PUBLIC_KEY,
                            "the host key on the wire is not the one the QR promised",
                        )
                    }
                    peerPublicKey = received
                    deriveIfReady()
                    if (crypto != null && !sentConfirmation) {
                        _state.value = LinkState.Confirming
                        outgoing.send(sealedConfirmation())
                        sentConfirmation = true
                    }
                }
                LinkMessage.TYPE_CFM -> {
                    // May arrive before this side has derived, so it is held rather
                    // than verified on the spot.
                    pendingPeerMac = Base64Url.decodeOrNull(message.mac.orEmpty())
                        ?: throw LinkCryptoException(
                            LinkCryptoException.Reason.CONFIRMATION_FAILED,
                            "the peer's cfm carried a mac that is not base64url",
                        )
                }
                // Nothing else is accepted, or even looked at, before confirmation.
                else -> log?.d(TAG, "ignored a ${message.type} frame during the handshake")
            }
        }

        val session = crypto ?: throw LinkCryptoException(
            LinkCryptoException.Reason.CONFIRMATION_FAILED,
            "the handshake ended without a key schedule",
        )
        // Throws if it does not verify, which is the MITM case: a relay that
        // substituted a key derived a different schedule and cannot produce this.
        session.verifyPeerConfirmation(pendingPeerMac)
    }

    private suspend fun sealedConfirmation(): Frame.Text {
        val session = crypto ?: error("confirmation before the schedule exists")
        return Frame.Text(
            LinkCodec.encode(LinkMessage.cfm(memberId, Base64Url.encode(session.confirmation())))
        )
    }

    /** Wraps [message] in `enc` and writes it. Every application frame goes this way. */
    private suspend fun DefaultClientWebSocketSession.sendSealed(message: LinkMessage) {
        val session = crypto ?: return
        val record = session.seal(
            plaintext = LinkCodec.encode(message.copy(from = memberId)).encodeToByteArray(),
            from = memberId,
        )
        outgoing.send(
            Frame.Text(
                LinkCodec.encode(
                    LinkMessage.enc(memberId, record.counter, Base64Url.encode(record.ciphertext))
                )
            )
        )
    }

    private suspend fun onFrame(text: String) {
        val envelope = LinkCodec.decode(text)
        if (envelope == null) {
            log?.d(TAG, "dropped an unparseable frame (${text.length} chars)")
            return
        }
        if (envelope.from == memberId) return // the relay never does this; belt and braces

        // The relay's reconnect ring replays the handshake frames too. Once confirmed
        // they mean nothing, and re-running the handshake over them would be wrong.
        if (envelope.type == LinkMessage.TYPE_KEX || envelope.type == LinkMessage.TYPE_CFM) return

        if (envelope.type != LinkMessage.TYPE_ENC) {
            // After the handshake, a plaintext application frame is either a peer
            // that thinks the session is unencrypted or somebody injecting. Neither
            // is something to act on.
            log?.d(TAG, "dropped a plaintext ${envelope.type} frame on an encrypted session")
            return
        }

        val session = crypto ?: return
        val ciphertext = Base64Url.decodeOrNull(envelope.ct.orEmpty()) ?: return
        val counter = envelope.n ?: return
        val plaintext = try {
            session.open(ciphertext, counter, envelope.from)
        } catch (e: LinkCryptoException) {
            if (e.reason == LinkCryptoException.Reason.REPLAY) {
                // Not an attack to die over — it is the relay's replay ring doing
                // exactly what #33 built it to do. Dropping the frame *is* the replay
                // protection; tearing the session down would instead let anyone kill
                // a conversation by echoing one frame back into it.
                log?.d(TAG, "dropped a replayed record ($counter)")
                return
            }
            // A tag failure is different: the frame was altered, or the keys do not
            // match. §3.7 — fail closed.
            throw e
        }
        val message = LinkCodec.decode(plaintext.decodeToString()) ?: return

        when (message.type) {
            LinkMessage.TYPE_HELLO -> {
                peerPresent = true
                peerLang = message.lang
                peerName = message.name
                _state.value = LinkState.Connected(
                    peerPresent = true,
                    peerLang = message.lang,
                    peerName = message.name,
                    verification = verification,
                    sas = session.sas,
                )
            }
            LinkMessage.TYPE_BYE -> _state.value = LinkState.Failed(LinkFailure.PEER_LEFT)
            else -> {
                // Anything at all from the peer proves it is there, even if its
                // `hello` was the frame that went missing.
                peerPresent = true
                val current = _state.value
                if (current is LinkState.Connected && !current.peerPresent) {
                    _state.value = current.copy(peerPresent = true)
                }
            }
        }
        _inbound.emit(message)
    }

    /** Records [message] as possibly-not-delivered, keeping the window bounded. */
    private fun remember(message: LinkMessage) {
        unconfirmed.addLast(message)
        while (unconfirmed.size > RESEND_WINDOW) unconfirmed.removeFirst()
    }

    /**
     * The secret half of the hybrid input. The QR carries 256 bits; the typed code
     * carries about 30 and is honest about it (§5). No secret at all means the
     * session cannot start.
     */
    private fun secretFor(ticket: SessionTicket): ByteArray? =
        ticket.qrSecret?.let(Base64Url::decodeOrNull)
            ?: ticket.manualCode?.encodeToByteArray()

    private fun wipeKeys() {
        exchange = null
        crypto = null
        sessionSecret = null
        verification = Verification.NONE
    }

    /** `wss://<relay>/s/<sid>?m=<memberId>` — the secret appears in neither. */
    private fun sessionUrl(sessionId: String, member: String): String =
        "${relayBaseUrl.trimEnd('/')}/s/$sessionId?m=$member"

    private companion object {
        const val TAG = "ConversationLink"
        const val INBOX_CAPACITY = 64
        const val OUTBOX_CAPACITY = 64
        const val MAX_ATTEMPTS = 5
        const val RETRY_BASE_DELAY_MS = 500L
        const val SHUTDOWN_TIMEOUT_MS = 1000L
        const val QR_SECRET_BYTES = 32

        /**
         * A connection has to last this long before it clears the attempt counter.
         * Long enough that only a session which actually worked resets the backoff,
         * short enough that a real conversation always does.
         */
        const val STABLE_CONNECTION_MS = 5_000L

        /**
         * How many recent frames are re-sent on reconnect. Small on purpose: the
         * relay caps a session at 40 frames per 10 seconds, and a conversation
         * turn takes seconds to speak, so a handful covers any realistic blip.
         */
        const val RESEND_WINDOW = 5

        /**
         * The relay's own rejections: 1003 non-text frame, 1008 rate cap, 1009 frame
         * too large. All three mean this client did something the protocol forbids, so
         * reconnecting would only do it again.
         */
        val PROTOCOL_CLOSE_CODES = setOf(1003, 1008, 1009)
    }
}
