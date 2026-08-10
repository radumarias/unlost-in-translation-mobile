package com.example.link

import com.example.link.crypto.LinkRole
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
 * The transport, against a real WebSocket server ([FakeRelayServer]) and with the
 * real issue #34 handshake — two links, two ephemeral key pairs, a genuine ECDH
 * agreement and AES-GCM on every frame.
 *
 * JVM host only, because Ktor's server is JVM-only. The code under test is 100%
 * `commonMain` and byte-identical on wasm, the crypto is pinned on both platforms by
 * `SessionCryptoTest`, and the Phase 0 spike proved the same client opens and echoes
 * a real socket in ChromeHeadless.
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

    /** A host and a guest that have completed the handshake against each other. */
    private suspend fun pairedSession(
        useManualCode: Boolean = false,
    ): Triple<WebSocketConversationLink, WebSocketConversationLink, SessionTicket> {
        val host = link("host-1")
        val guest = link("guest-1")
        val ticket = host.createHostTicket(hostLang = "ro", useManualCode = useManualCode)
        host.connect(ticket, myLang = "ro", role = LinkRole.HOST)
        // The guest holds exactly what the QR carried — or, on the manual path, what
        // the six typed characters resolve to.
        guest.connect(ticket, myLang = "en", role = LinkRole.GUEST)
        return Triple(host, guest, ticket)
    }

    // --- the handshake ---

    @Test
    fun twoPhonesCompleteTheHandshakeAndTheHelloArrivesEncrypted() = runBlocking {
        val (host, guest, _) = pairedSession()
        val state = guest.awaitConnectedPeer()

        assertEquals("ro", state.peerLang)
        assertEquals(Verification.VERIFIED_BY_QR, state.verification)
        assertEquals(Verification.VERIFIED_BY_QR, host.awaitConnectedPeer().verification)

        // The relay saw the handshake and then nothing but sealed envelopes — it does
        // not even learn which languages are being spoken.
        val onTheWire = relay.ringOf("host-1") + relay.ringOf("guest-1")
        assertTrue(onTheWire.any { "\"kex\"" in it }, "kex must be in the clear")
        assertTrue(onTheWire.any { "\"cfm\"" in it }, "cfm must be in the clear")
        assertTrue(onTheWire.none { "\"hello\"" in it }, "hello must be encrypted")
        assertTrue(onTheWire.none { "\"ro\"" in it }, "the relay must not learn the languages")
    }

    @Test
    fun theManualCodePathAlsoCompletesButAsksForTheSasCheck() = runBlocking {
        val (_, guest, ticket) = pairedSession(useManualCode = true)
        val state = guest.awaitConnectedPeer()

        assertTrue(ticket.manualCode != null)
        // The typed code carries ~30 bits and cannot authenticate the host's key, so
        // the two people must compare the SAS. The UI is issue #34c; the state that
        // drives it is here.
        assertEquals(Verification.NEEDS_SAS_CHECK, state.verification)
        assertEquals(5, state.sas?.length)
    }

    @Test
    fun bothPhonesComputeTheSameSas() = runBlocking {
        val (host, guest, _) = pairedSession(useManualCode = true)
        val hostSas = host.awaitConnectedPeer().sas
        val guestSas = guest.awaitConnectedPeer().sas
        // If these ever differed, the check the user is asked to perform would be
        // theatre — and the user would be the one blamed for the mismatch.
        assertEquals(hostSas, guestSas)
        assertTrue(hostSas!!.all { it.isDigit() })
    }

    @Test
    fun nothingIsDeliveredBeforeTheHandshakeCompletes() = runBlocking {
        val host = link("host-1")
        val guest = link("guest-1")
        val inbox = guest.collectInbound()
        val ticket = host.createHostTicket(hostLang = "ro")

        // A guest alone on the session: no peer, so no handshake can complete.
        guest.connect(ticket, myLang = "en", role = LinkRole.GUEST)
        delay(600)

        assertTrue(inbox.isEmpty(), "nothing may reach the app before confirmation")
        assertTrue(guest.state.value !is LinkState.Connected)
    }

    @Test
    fun aTicketWithNoSecretIsRefusedRatherThanDowngraded() = runBlocking {
        val guest = link("guest-1")
        // A #33-era ticket: routing only, no `k`. There is no plaintext fallback.
        guest.connect(
            SessionTicket(sessionId = SessionTicket.newSessionId(), hostLang = "ro"),
            myLang = "en",
            role = LinkRole.GUEST,
        )
        guest.awaitState { it is LinkState.Failed && it.reason == LinkFailure.ENCRYPTION_FAILED }
    }

    @Test
    fun aHostKeyThatDisagreesWithTheQrEndsTheSession() = runBlocking {
        // The MITM case as it reaches the wire: the guest scanned one host key and a
        // different one arrives in `kex`. That is not a discrepancy to reconcile.
        val realHost = link("host-1")
        val ticket = realHost.createHostTicket(hostLang = "ro")

        val impostor = link("impostor-1")
        val guest = link("guest-1")
        // The impostor runs its own session on the same id with its own key pair.
        val impostorTicket = impostor.createHostTicket(hostLang = "ro").copy(sessionId = ticket.sessionId)
        impostor.connect(impostorTicket, myLang = "ro", role = LinkRole.HOST)
        guest.connect(ticket, myLang = "en", role = LinkRole.GUEST)

        guest.awaitState(timeoutMs = 8_000) {
            it is LinkState.Failed && it.reason == LinkFailure.ENCRYPTION_FAILED
        }
    }

    // --- turns, now sealed ---

    @Test
    fun aTurnReachesThePeerAndNotItsSender() = runBlocking {
        val (host, guest, _) = pairedSession()
        val guestInbox = guest.collectInbound()
        val hostInbox = host.collectInbound()
        guest.awaitConnectedPeer()

        host.send(LinkMessage.turn("ignored", "t-1", "ro", "Bună", "Hello"))

        val turn = guestInbox.awaitFirst { it.type == LinkMessage.TYPE_TURN }
        assertEquals("Bună", turn.original)
        assertEquals("Hello", turn.translated)
        assertTrue(hostInbox.none { it.type == LinkMessage.TYPE_TURN }, "a sender must not hear itself")
        assertTrue(
            relay.ringOf("host-1").none { "Bună" in it },
            "the relay must never see the plaintext of a turn",
        )
    }

    @Test
    fun theLinkStampsItsOwnMemberIdOnOutgoingFrames() = runBlocking {
        val (host, guest, _) = pairedSession()
        val guestInbox = guest.collectInbound()
        guest.awaitConnectedPeer()

        host.send(LinkMessage.turn("somebody-else", "t-1", "ro", "a", "b"))

        assertEquals("host-1", guestInbox.awaitFirst { it.type == LinkMessage.TYPE_TURN }.from)
    }

    @Test
    fun aTurnSpokenDuringABlipArrivesWhenTheSocketComesBack() = runBlocking {
        val (host, guest, _) = pairedSession()
        val guestInbox = guest.collectInbound()
        guest.awaitConnectedPeer()

        relay.drop("guest-1")
        host.send(LinkMessage.turn("host-1", "t-1", "ro", "spoken", "while away"))

        // The reconnect keeps the same member id *and* the same keys: issue #34 §4
        // says the handshake is not redone, and the counters carry on.
        val turn = guestInbox.awaitFirst(timeoutMs = 8_000) { it.id == "t-1" }
        assertEquals("while away", turn.translated)
        assertEquals("guest-1", guest.memberId)
    }

    @Test
    fun aTurnQueuedWhileTheSocketIsDownIsSentOnReconnect() = runBlocking {
        val (host, guest, _) = pairedSession()
        val guestInbox = guest.collectInbound()
        guest.awaitConnectedPeer()

        relay.drop("host-1")
        host.send(LinkMessage.turn("host-1", "t-9", "ro", "queued", "queued"))

        assertEquals("queued", guestInbox.awaitFirst(timeoutMs = 8_000) { it.id == "t-9" }.original)
    }

    @Test
    fun aTurnWrittenIntoADyingSocketIsResentRatherThanLost() = runBlocking {
        val (host, guest, _) = pairedSession()
        val guestInbox = guest.collectInbound()
        guest.awaitConnectedPeer()

        repeat(6) { i ->
            host.send(LinkMessage.turn("host-1", "r-$i", "ro", "line $i", "line $i"))
            if (i == 2) relay.drop("host-1")
        }

        guestInbox.awaitFirst(timeoutMs = 10_000) { it.id == "r-5" }
        val ids = guestInbox.filter { it.type == LinkMessage.TYPE_TURN }.map { it.id }.toSet()
        assertEquals((0..5).map { "r-$it" }.toSet(), ids, "no turn may be dropped by a blip")
    }

    @Test
    fun theRelaysReplayRingDoesNotKillTheSession() = runBlocking {
        // The ring hands a reconnecting device frames it has already seen. Those are
        // replays by construction, and dropping them *is* the replay protection —
        // tearing the session down would let anyone kill a conversation by echoing
        // one frame back into it.
        val (host, guest, _) = pairedSession()
        val guestInbox = guest.collectInbound()
        guest.awaitConnectedPeer()

        host.send(LinkMessage.turn("host-1", "t-1", "ro", "one", "one"))
        guestInbox.awaitFirst { it.id == "t-1" }

        relay.drop("guest-1")
        host.send(LinkMessage.turn("host-1", "t-2", "ro", "two", "two"))

        // On reconnect the guest is replayed t-1 as well as t-2.
        guestInbox.awaitFirst(timeoutMs = 8_000) { it.id == "t-2" }
        assertTrue(guest.state.value is LinkState.Connected, "a replay must not end the session")
    }

    @Test
    fun disconnectTellsThePeerRatherThanJustVanishing() = runBlocking {
        val (host, guest, _) = pairedSession()
        val guestInbox = guest.collectInbound()
        guest.awaitConnectedPeer()

        host.disconnect()

        guestInbox.awaitFirst { it.type == LinkMessage.TYPE_BYE }
        guest.awaitState { it is LinkState.Failed && it.reason == LinkFailure.PEER_LEFT }
        assertEquals(LinkState.Idle, host.state.value)
    }

    // --- failures that are not the peer's fault ---

    @Test
    fun aBlankRelayUrlIsReportedAsUnconfiguredRatherThanAsANetworkError() = runBlocking {
        val link = link("host-1", baseUrl = "")
        val ticket = link.createHostTicket(hostLang = "ro")
        link.connect(ticket, myLang = "ro", role = LinkRole.HOST)
        assertEquals(LinkState.Failed(LinkFailure.NOT_CONFIGURED), link.state.value)
    }

    @Test
    fun anUnreachableRelayGivesUpAsANetworkFailure() = runBlocking {
        // Port 1 is reserved and never listening.
        val link = link("host-1", baseUrl = "ws://127.0.0.1:1")
        val ticket = link.createHostTicket(hostLang = "ro")
        link.connect(ticket, myLang = "ro", role = LinkRole.HOST)
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
        val ticket = link.createHostTicket(hostLang = "ro")
        link.connect(ticket, myLang = "ro", role = LinkRole.HOST)

        link.awaitState(timeoutMs = 10_000) {
            it is LinkState.Failed && it.reason == LinkFailure.NETWORK
        }
    }

    @Test
    fun whatThePeerSaidAboutItselfSurvivesAReconnect() = runBlocking {
        val host = link("host-1")
        val guest = link("guest-1")
        val ticket = host.createHostTicket(hostLang = "ro")
        host.connect(ticket, myLang = "ro", myName = "Radu", role = LinkRole.HOST)
        guest.connect(ticket, myLang = "en", role = LinkRole.GUEST)
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
        val (host, guest, _) = pairedSession()
        val guestInbox = guest.collectInbound()
        guest.awaitConnectedPeer()

        sendRaw("host-1", "{\"v\":9,\"type\":\"whatever\",\"from\":\"host-1\"}")
        host.send(LinkMessage.turn("host-1", "t-2", "ro", "still", "here"))

        assertEquals("here", guestInbox.awaitFirst { it.id == "t-2" }.translated)
        assertTrue(guest.state.value is LinkState.Connected)
    }

    @Test
    fun aForgedRecordEndsTheSession() = runBlocking {
        // A frame that is well-formed but does not authenticate is not a dropped
        // frame — it means someone is writing into the session. Fail closed (§3.7).
        val (_, guest, _) = pairedSession()
        guest.awaitConnectedPeer()

        relay.injectFrom(
            "host-1",
            """{"v":1,"type":"enc","from":"host-1","n":9000,"ct":"AAAAAAAAAAAAAAAAAAAAAAAAAAAA"}""",
        )

        guest.awaitState(timeoutMs = 8_000) {
            it is LinkState.Failed && it.reason == LinkFailure.ENCRYPTION_FAILED
        }
    }

    // --- helpers ---

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

    private suspend fun ConversationLink.awaitConnectedPeer(timeoutMs: Long = 8_000): LinkState.Connected {
        awaitState(timeoutMs) { it is LinkState.Connected && it.peerPresent }
        return state.value as LinkState.Connected
    }

    private suspend fun ConversationLink.awaitState(
        timeoutMs: Long = 8_000,
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
        timeoutMs: Long = 8_000,
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
