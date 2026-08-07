package com.example.link

import com.example.link.crypto.LinkRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The two-device conversation seam (issue #33 Phase 2), shaped like
 * `wear.TranslationBroadcaster` but bidirectional: the view model talks to this and
 * never to a socket, so the whole feature is testable with a fake and the web build
 * can supply [OfflineConversationLink] without an `expect`/`actual` in sight.
 *
 * [state] is a `StateFlow` on purpose. The view model's `messages` is a no-replay
 * `SharedFlow`, so a connection event emitted before the UI's collector attaches
 * would simply be lost — a banner needs current state, not a past notification.
 */
interface ConversationLink {
    /** Current connection state. Always has a value; starts at [LinkState.Idle]. */
    val state: StateFlow<LinkState>

    /** Frames from the peer. Never emits this device's own frames — the relay fans out. */
    val inbound: Flow<LinkMessage>

    /**
     * This device's per-session id, used as `from` on outgoing frames. Regenerated on
     * every [connect], so it cannot link one conversation to another.
     */
    val memberId: String

    /**
     * Mints the ticket this device will publish as host (issue #34): a fresh
     * session id, a 256-bit QR secret and an ephemeral P-256 public key, or — when
     * [useManualCode] — a six-character code in place of the first two.
     *
     * On the link rather than on the view model because the private half of that
     * key pair must never leave this object. The link remembers it for the
     * [connect] that follows.
     */
    suspend fun createHostTicket(hostLang: String?, useManualCode: Boolean = false): SessionTicket

    /**
     * Joins [ticket]'s session as [role] and, once the handshake succeeds,
     * announces this device with an encrypted `hello`. Returns as soon as the
     * attempt is under way; progress and failure arrive on [state]. Safe to call
     * when already connected — the previous session is torn down first.
     */
    suspend fun connect(
        ticket: SessionTicket,
        myLang: String,
        myName: String? = null,
        role: LinkRole = LinkRole.GUEST,
    )

    /**
     * Queues [message] for the peer. The link stamps `from` with its own [memberId],
     * so a caller cannot accidentally send someone else's id. Queued rather than
     * written straight out: a turn spoken during a ten-second blip is delivered when
     * the socket comes back rather than lost.
     */
    suspend fun send(message: LinkMessage)

    /** Sends `bye` if it can, closes the socket and returns to [LinkState.Idle]. */
    suspend fun disconnect()
}

/** Where a session is, from the point of view of the banner the user sees. */
sealed interface LinkState {
    /** Not in a remote session. */
    data object Idle : LinkState

    /** Opening the socket, or reopening it after a drop. */
    data object Connecting : LinkState

    /** Socket open, ephemeral public keys being exchanged (issue #34 §3.3). */
    data object KeyExchange : LinkState

    /** Keys derived; each side is proving to the other that it derived the same ones (§3.5). */
    data object Confirming : LinkState

    /**
     * The socket is open. [peerPresent] only turns true once the other device has
     * actually said something — the relay reports no presence, and pretending
     * otherwise would show "connected" to someone standing alone.
     *
     * [verification] and [sas] are issue #34's and are [Verification.NONE] / null
     * until it lands; they are declared here so the UI written for #33 does not have
     * to change shape when encryption arrives.
     */
    data class Connected(
        val peerPresent: Boolean = false,
        val peerLang: String? = null,
        val peerName: String? = null,
        val verification: Verification = Verification.NONE,
        val sas: String? = null,
    ) : LinkState

    /** Terminal. The session is over and the reason is worth telling the user. */
    data class Failed(val reason: LinkFailure) : LinkState
}

/**
 * How much the two devices can trust that nobody is in the middle (issue #34 §5).
 * Until #34 lands every session is [NONE]: encrypted in transport by TLS, but
 * readable by the relay operator.
 */
enum class Verification {
    /** No end-to-end encryption yet — issue #33's state of the world. */
    NONE,

    /** The QR carried the host's key, so the peer is authenticated with no user step. */
    VERIFIED_BY_QR,

    /** Manual-code path: both people must compare the SAS before anything is rendered. */
    NEEDS_SAS_CHECK,
}

/**
 * Why a session ended. An enum rather than a message so the UI can translate it —
 * no user-visible string is built in the link layer (`HANDOFF.md` hard rule 4b).
 */
enum class LinkFailure {
    /** `RELAY_BASE_URL` is not set in this build. */
    NOT_CONFIGURED,

    /** Two devices are already in this session. */
    SESSION_FULL,

    /** The session id expired or the relay dropped it. */
    SESSION_EXPIRED,

    /** The socket could not be opened or kept open. */
    NETWORK,

    /** The peer sent something this build cannot make sense of. */
    PROTOCOL,

    /** The peer left. */
    PEER_LEFT,

    /**
     * The handshake failed, or a frame did not authenticate (issue #34 §3.7).
     * There is no plaintext fallback: this ends the session.
     */
    ENCRYPTION_FAILED,
}

/**
 * The link on a platform that has no remote conversation — the web build in v1.
 * Mirrors `WebTranslationBroadcaster`: present so nothing has to null-check, inert
 * so nothing happens. The UI never reaches it anyway, because
 * `PlatformCapabilities.remoteConversation` is false wherever this is installed.
 */
object OfflineConversationLink : ConversationLink {
    override val state: StateFlow<LinkState> = MutableStateFlow(LinkState.Idle)
    override val inbound: Flow<LinkMessage> = emptyFlow()
    override val memberId: String = ""
    override suspend fun createHostTicket(hostLang: String?, useManualCode: Boolean): SessionTicket =
        SessionTicket(sessionId = SessionTicket.newSessionId(), hostLang = hostLang)

    override suspend fun connect(
        ticket: SessionTicket,
        myLang: String,
        myName: String?,
        role: LinkRole,
    ) = Unit
    override suspend fun send(message: LinkMessage) = Unit
    override suspend fun disconnect() = Unit
}
