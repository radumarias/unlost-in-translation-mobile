package com.example.link

import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The transport, against a real WebSocket server ([FakeRelayServer]).
 *
 * JVM host only, because Ktor's server is JVM-only — but the code under test is
 * 100% `commonMain` and byte-identical on wasm, and the Phase 0 spike already
 * proved the same client opens and echoes on a real socket in ChromeHeadless.
 */
class WebSocketConversationLinkTest {

    private lateinit var relay: FakeRelayServer
    private val scopes = mutableListOf<CoroutineScope>()

    @BeforeTest
    fun setUp() = runBlocking {
        relay = FakeRelayServer()
        relay.start()
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
        relay.stop()
    }

    private fun link(memberId: String, baseUrl: String = relay.baseUrl): WebSocketConversationLink {
        val scope = CoroutineScope(SupervisorJob())
        scopes += scope
        return WebSocketConversationLink(
            client = linkHttpClient(OkHttp.create()),
            relayBaseUrl = baseUrl,
            scope = scope,
            newMemberId = { memberId },
            maxAttempts = 3,
            retryBaseDelayMs = 50,
        )
    }

    private val ticket = SessionTicket(sessionId = SessionTicket.newSessionId(), hostLang = "ro")

    @Test
    fun theHelloReachesThePeerWithItsLanguage() = runBlocking {
        val host = link("host-1")
        val guest = link("guest-1")
        host.connect(ticket, myLang = "ro", myName = "Radu")
        guest.connect(ticket, myLang = "en")

        val state = guest.awaitConnectedPeer()
        assertEquals("ro", state.peerLang)
        assertEquals("Radu", state.peerName)
    }

    @Test
    fun aTurnReachesThePeerAndNotItsSender() = runBlocking {
        val host = link("host-1")
        val guest = link("guest-1")
        val guestInbox = guest.collectInbound()
        val hostInbox = host.collectInbound()
        host.connect(ticket, myLang = "ro")
        guest.connect(ticket, myLang = "en")
        guest.awaitConnectedPeer()

        host.send(LinkMessage.turn("ignored", "t-1", "ro", "Bună", "Hello"))

        val turn = guestInbox.awaitFirst { it.type == LinkMessage.TYPE_TURN }
        assertEquals("Bună", turn.original)
        assertEquals("Hello", turn.translated)
        assertEquals("ro", turn.lang)
        assertTrue(hostInbox.none { it.type == LinkMessage.TYPE_TURN }, "a sender must not hear itself")
    }

    @Test
    fun theLinkStampsItsOwnMemberIdOnOutgoingFrames() = runBlocking {
        val host = link("host-1")
        val guest = link("guest-1")
        val guestInbox = guest.collectInbound()
        host.connect(ticket, myLang = "ro")
        guest.connect(ticket, myLang = "en")
        guest.awaitConnectedPeer()

        // Whatever the caller put in `from`, the frame must carry the link's id — the
        // peer de-duplicates and attributes turns by it.
        host.send(LinkMessage.turn("somebody-else", "t-1", "ro", "a", "b"))

        val turn = guestInbox.awaitFirst { it.type == LinkMessage.TYPE_TURN }
        assertEquals("host-1", turn.from)
    }

    @Test
    fun aTurnSpokenDuringABlipArrivesWhenTheSocketComesBack() = runBlocking {
        val host = link("host-1")
        val guest = link("guest-1")
        val guestInbox = guest.collectInbound()
        host.connect(ticket, myLang = "ro")
        guest.connect(ticket, myLang = "en")
        guest.awaitConnectedPeer()

        relay.drop("guest-1")
        host.send(LinkMessage.turn("host-1", "t-1", "ro", "spoken", "while away"))

        // The reconnect keeps the same member id — that is what makes the relay hand
        // back the peer's ring rather than treat this as a third device.
        val turn = guestInbox.awaitFirst(timeoutMs = 8_000) { it.id == "t-1" }
        assertEquals("while away", turn.translated)
        assertEquals("guest-1", guest.memberId)
    }

    @Test
    fun aTurnQueuedWhileTheSocketIsDownIsSentOnReconnect() = runBlocking {
        val host = link("host-1")
        val guest = link("guest-1")
        val guestInbox = guest.collectInbound()
        host.connect(ticket, myLang = "ro")
        guest.connect(ticket, myLang = "en")
        guest.awaitConnectedPeer()

        relay.drop("host-1")
        // No socket right now: without the outbox this turn would simply be lost.
        host.send(LinkMessage.turn("host-1", "t-9", "ro", "queued", "queued"))

        val turn = guestInbox.awaitFirst(timeoutMs = 8_000) { it.id == "t-9" }
        assertEquals("queued", turn.original)
    }

    @Test
    fun aTurnWrittenIntoADyingSocketIsResentRatherThanLost() = runBlocking {
        val host = link("host-1")
        val guest = link("guest-1")
        val guestInbox = guest.collectInbound()
        host.connect(ticket, myLang = "ro")
        guest.connect(ticket, myLang = "en")
        guest.awaitConnectedPeer()

        // The nastiest case, and the one an outbox alone does not cover: the frame
        // leaves the queue and is written to a socket that is already gone, which
        // looks like a successful write. Racing the drop is the point.
        repeat(6) { i ->
            host.send(LinkMessage.turn("host-1", "r-$i", "ro", "line $i", "line $i"))
            if (i == 2) relay.drop("host-1")
        }

        val last = guestInbox.awaitFirst(timeoutMs = 10_000) { it.id == "r-5" }
        assertEquals("line 5", last.translated)
        val ids = guestInbox.filter { it.type == LinkMessage.TYPE_TURN }.map { it.id }.toSet()
        assertEquals((0..5).map { "r-$it" }.toSet(), ids, "no turn may be dropped by a blip")
    }

    @Test
    fun disconnectTellsThePeerRatherThanJustVanishing() = runBlocking {
        val host = link("host-1")
        val guest = link("guest-1")
        val guestInbox = guest.collectInbound()
        host.connect(ticket, myLang = "ro")
        guest.connect(ticket, myLang = "en")
        guest.awaitConnectedPeer()

        host.disconnect()

        guestInbox.awaitFirst { it.type == LinkMessage.TYPE_BYE }
        guest.awaitState { it is LinkState.Failed && it.reason == LinkFailure.PEER_LEFT }
        assertEquals(LinkState.Idle, host.state.value)
    }

    @Test
    fun aBlankRelayUrlIsReportedAsUnconfiguredRatherThanAsANetworkError() = runBlocking {
        val link = link("host-1", baseUrl = "")
        link.connect(ticket, myLang = "ro")
        assertEquals(LinkState.Failed(LinkFailure.NOT_CONFIGURED), link.state.value)
    }

    @Test
    fun anUnreachableRelayGivesUpAsANetworkFailure() = runBlocking {
        // Port 1 is reserved and never listening.
        val link = link("host-1", baseUrl = "ws://127.0.0.1:1")
        link.connect(ticket, myLang = "ro")
        link.awaitState(timeoutMs = 10_000) { it is LinkState.Failed && it.reason == LinkFailure.NETWORK }
    }

    @Test
    fun aRelayThatAcceptsAndHangsUpDoesNotRetryForEver() = runBlocking {
        // The upgrade succeeding is not the same as the session working. An expired
        // session accepts and hangs up, and a client that treats "the socket opened"
        // as progress resets its attempt counter every time round — so it never
        // reaches the cap, and reconnects until the battery is gone.
        relay.hangUpImmediately = true
        val link = link("host-1")
        link.connect(ticket, myLang = "ro")

        link.awaitState(timeoutMs = 10_000) {
            it is LinkState.Failed && it.reason == LinkFailure.NETWORK
        }
    }

    @Test
    fun whatThePeerSaidAboutItselfSurvivesAReconnect() = runBlocking {
        val host = link("host-1")
        val guest = link("guest-1")
        host.connect(ticket, myLang = "ro", myName = "Radu")
        guest.connect(ticket, myLang = "en")
        guest.awaitConnectedPeer()

        relay.drop("guest-1")

        // Rebuilding the state from defaults on reconnect would say "waiting for the
        // other phone" about a peer that never went anywhere — and once the frames
        // are encrypted the peer's replayed `hello` is refused as a replay, so there
        // is no second chance to learn this.
        val state = guest.awaitConnectedPeer(timeoutMs = 10_000)
        assertEquals("ro", state.peerLang)
        assertEquals("Radu", state.peerName)
    }

    @Test
    fun anUnparseableFrameCostsTheFrameAndNotTheSession() = runBlocking {
        val host = link("host-1")
        val guest = link("guest-1")
        val guestInbox = guest.collectInbound()
        host.connect(ticket, myLang = "ro")
        guest.connect(ticket, myLang = "en")
        guest.awaitConnectedPeer()

        // A peer on a protocol this build does not know. Dropping the session over it
        // would make every future protocol change a hard break.
        sendRaw("host-1", "{\"v\":9,\"type\":\"whatever\",\"from\":\"host-1\"}")
        host.send(LinkMessage.turn("host-1", "t-2", "ro", "still", "here"))

        val turn = guestInbox.awaitFirst { it.id == "t-2" }
        assertEquals("here", turn.translated)
        assertTrue(guest.state.value is LinkState.Connected)
    }

    // --- helpers ---

    /**
     * Puts text on the wire that the [LinkMessage] factories could not produce. It
     * goes in at the relay rather than through the sending link, because the point
     * under test is the *receiver's* tolerance, not a sender's ability to misbehave.
     */
    private suspend fun sendRaw(from: String, text: String) {
        if (LinkCodec.decode(text) != null) fail("this test needs a frame the codec rejects")
        relay.injectFrom(from, text)
    }

    /**
     * Copy-on-write, not `mutableListOf`: the collector runs on `Dispatchers.Default`
     * while the test thread iterates the same list in `awaitFirst`, `filter` and
     * `none`. A plain list makes every one of these tests intermittently throw
     * `ConcurrentModificationException`, and the flake would read as a transport bug.
     */
    private fun ConversationLink.collectInbound(): MutableList<LinkMessage> {
        val received = CopyOnWriteArrayList<LinkMessage>()
        val scope = CoroutineScope(SupervisorJob())
        scopes += scope
        scope.launch { inbound.collect { received += it } }
        return received
    }

    private suspend fun ConversationLink.awaitConnectedPeer(timeoutMs: Long = 5_000): LinkState.Connected {
        awaitState(timeoutMs) { it is LinkState.Connected && it.peerPresent }
        return state.value as LinkState.Connected
    }

    private suspend fun ConversationLink.awaitState(
        timeoutMs: Long = 5_000,
        predicate: (LinkState) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(state.value)) return
            delay(POLL_MS)
        }
        fail("timed out waiting for a state; last was ${state.value}")
    }

    private suspend fun List<LinkMessage>.awaitFirst(
        timeoutMs: Long = 5_000,
        predicate: (LinkMessage) -> Boolean,
    ): LinkMessage {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            firstOrNull(predicate)?.let { return it }
            delay(POLL_MS)
        }
        fail("timed out waiting for a frame; received ${this.map { it.type to it.id }}")
    }

    private companion object {
        const val POLL_MS = 20L
    }
}
