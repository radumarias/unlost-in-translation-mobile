package com.example.ui.viewmodel.fakes

import com.example.link.ConversationLink
import com.example.link.LinkMessage
import com.example.link.LinkState
import com.example.link.SessionTicket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Records what the view model sent and lets a test push frames back, in the shape
 * of `RecordingBroadcaster`. Nothing here touches a socket.
 */
class RecordingConversationLink(override val memberId: String = "me") : ConversationLink {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    // Replaying, like the relay's ring: a frame emitted before the view model's
    // collector attaches is delivered rather than dropped, which is what the real
    // relay does after a reconnect and what keeps these tests free of start-order
    // races that say nothing about the code under test.
    private val _inbound = MutableSharedFlow<LinkMessage>(replay = 32)
    override val inbound: Flow<LinkMessage> = _inbound.asSharedFlow()

    val sent = mutableListOf<LinkMessage>()
    val connects = mutableListOf<SessionTicket>()
    var disconnects = 0
        private set

    /**
     * Thrown out of [connect] when set. The real link reports most failures through
     * [state], but a relay URL that is not a URL at all throws before there is any
     * state to report — and a throw out of a `viewModelScope.launch` is a crash.
     */
    var connectFailure: Throwable? = null

    override suspend fun connect(ticket: SessionTicket, myLang: String, myName: String?) {
        connects += ticket
        connectFailure?.let { throw it }
        _state.value = LinkState.Connected()
    }

    override suspend fun send(message: LinkMessage) {
        sent += message.copy(from = memberId)
    }

    override suspend fun disconnect() {
        disconnects += 1
        _state.value = LinkState.Idle
    }

    /** Delivers [message] as if the peer had sent it. */
    suspend fun deliver(message: LinkMessage) {
        _inbound.emit(message)
    }

    fun setState(state: LinkState) {
        _state.value = state
    }
}

/**
 * Two links wired to each other, so a test can run two view models as two phones
 * with no relay in between.
 *
 * The fan-out rule is the relay's: a member never receives its own frame. That is
 * what makes the budget test below meaningful — if the receiving side ever needed a
 * translation call, this is where it would show up.
 */
class LoopbackConversationLink(override val memberId: String) : ConversationLink {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<LinkMessage>(replay = 64)
    override val inbound: Flow<LinkMessage> = _inbound.asSharedFlow()

    private var peer: LoopbackConversationLink? = null

    val sent = mutableListOf<LinkMessage>()

    override suspend fun connect(ticket: SessionTicket, myLang: String, myName: String?) {
        _state.value = LinkState.Connected()
        // The real link announces itself on connect; the peer's view model needs
        // that `hello` to learn which language to translate into.
        peer?._inbound?.emit(LinkMessage.hello(memberId, myLang, myName))
    }

    override suspend fun send(message: LinkMessage) {
        val stamped = message.copy(from = memberId)
        sent += stamped
        peer?._inbound?.emit(stamped)
    }

    override suspend fun disconnect() {
        _state.value = LinkState.Idle
        peer?._inbound?.emit(LinkMessage.bye(memberId))
    }

    companion object {
        /** A connected pair, `(a, b)`. */
        fun pair(): Pair<LoopbackConversationLink, LoopbackConversationLink> {
            val a = LoopbackConversationLink("member-a")
            val b = LoopbackConversationLink("member-b")
            a.peer = b
            b.peer = a
            return a to b
        }
    }
}
