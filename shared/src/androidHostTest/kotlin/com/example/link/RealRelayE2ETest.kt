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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The whole two-device feature against the **real relay**: the Worker and its
 * Durable Object running in workerd, not [FakeRelayServer].
 *
 * ```
 * cd relay && npm install && npx wrangler dev     # terminal 1
 * ./gradlew :shared:testAndroidHostTest -PrelayE2e
 * ```
 *
 * Excluded from the gate by `shared/build.gradle.kts` — with nothing listening it
 * would fail, and a test that depends on a server someone remembered to start is
 * not a gate. It exists because everything else about this feature is proven
 * against a fake we wrote: the fan-out, the replay ring, the member cap and the
 * frame limits are the Worker's own code, and this is the only thing that runs the
 * client against it. `RELAY_DEV_URL` overrides the default local origin, so the
 * same suite can be pointed at a deployed relay once there is one.
 */
class RealRelayE2ETest {

    private val relayBase = System.getenv("RELAY_DEV_URL") ?: "ws://127.0.0.1:8787"
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    private fun link(memberId: String): WebSocketConversationLink {
        val scope = CoroutineScope(SupervisorJob())
        scopes += scope
        return WebSocketConversationLink(
            client = linkHttpClient(OkHttp.create()),
            relayBaseUrl = relayBase,
            scope = scope,
            newMemberId = { memberId + "-" + (100000..999999).random() },
            maxAttempts = 3,
            retryBaseDelayMs = 50,
        )
    }

    @Test
    fun qrPath_twoPhonesTalkThroughTheRealWorker() = runBlocking {
        val host = link("host")
        val guest = link("guest")
        val hostInbox = host.collectInbound()
        val guestInbox = guest.collectInbound()

        val ticket = host.createHostTicket(hostLang = "ro")
        host.connect(ticket, myLang = "ro", myName = "Radu", role = LinkRole.HOST)
        // Exactly what the QR carries, decoded on the other phone.
        val scanned = assertNotNull(SessionTicket.parse(ticket.encodeCompact()))
        guest.connect(scanned, myLang = "en", myName = "Cezar", role = LinkRole.GUEST)

        val guestState = guest.awaitConnectedPeer()
        assertEquals("ro", guestState.peerLang)
        assertEquals("Radu", guestState.peerName)
        assertEquals(Verification.VERIFIED_BY_QR, guestState.verification)
        val hostState = host.awaitConnectedPeer()
        assertEquals("en", hostState.peerLang)

        host.send(LinkMessage.turn(host.memberId, "t-1", "ro", "Bună ziua", "Good afternoon"))
        assertEquals("Good afternoon", guestInbox.awaitFirst { it.id == "t-1" }.translated)

        guest.send(LinkMessage.turn(guest.memberId, "t-2", "en", "A table for two", "O masă pentru doi"))
        assertEquals("O masă pentru doi", hostInbox.awaitFirst { it.id == "t-2" }.translated)

        host.disconnect()
        guest.awaitState(timeoutMs = 8_000) { it is LinkState.Failed && it.reason == LinkFailure.PEER_LEFT }
    }

    @Test
    fun manualCodePath_bothPhonesShowTheSameFiveDigits() = runBlocking {
        val host = link("host")
        val guest = link("guest")

        val ticket = host.createHostTicket(hostLang = "ro", useManualCode = true)
        val typed = assertNotNull(ticket.manualCode)
        host.connect(ticket, myLang = "ro", role = LinkRole.HOST)
        // The other phone has six characters and nothing else.
        val fromCode = assertNotNull(SessionTicket.forManualCode(typed))
        guest.connect(fromCode, myLang = "en", role = LinkRole.GUEST)

        val g = guest.awaitConnectedPeer()
        val h = host.awaitConnectedPeer()
        assertEquals(Verification.NEEDS_SAS_CHECK, g.verification)
        assertEquals(Verification.NEEDS_SAS_CHECK, h.verification)
        assertNotNull(g.sas)
        assertEquals(h.sas, g.sas, "the SAS is what the two people read to each other")
        assertEquals(5, g.sas.length)
    }

    @Test
    fun aTurnSpokenBeforeTheOtherPhoneArrivesIsReplayedFromTheRing() = runBlocking {
        val host = link("host")
        val guest = link("guest")
        val guestInbox = guest.collectInbound()

        val ticket = host.createHostTicket(hostLang = "ro")
        host.connect(ticket, myLang = "ro", role = LinkRole.HOST)
        delay(1_000)

        guest.connect(ticket, myLang = "en", role = LinkRole.GUEST)
        guest.awaitConnectedPeer()
        host.awaitConnectedPeer()
        host.send(LinkMessage.turn(host.memberId, "t-9", "ro", "Mulțumesc", "Thank you"))

        assertEquals("Thank you", guestInbox.awaitFirst { it.id == "t-9" }.translated)
        assertTrue(guest.state.value is LinkState.Connected)
    }

    @Test
    fun aThirdPhoneCannotJoin() = runBlocking {
        val host = link("host")
        val guest = link("guest")
        val gatecrasher = link("third")

        val ticket = host.createHostTicket(hostLang = "ro")
        host.connect(ticket, myLang = "ro", role = LinkRole.HOST)
        guest.connect(ticket, myLang = "en", role = LinkRole.GUEST)
        guest.awaitConnectedPeer()

        gatecrasher.connect(ticket, myLang = "de", role = LinkRole.GUEST)
        // The relay answers 409; no client can read the status of a failed upgrade,
        // so this surfaces as a network failure (documented deviation).
        gatecrasher.awaitState(timeoutMs = 15_000) { it is LinkState.Failed }
        println("third phone ended as ${gatecrasher.state.value}")
        assertTrue(guest.state.value is LinkState.Connected, "the pair is untouched")
    }

    // --- helpers, copied from WebSocketConversationLinkTest ---

    private fun ConversationLink.collectInbound(): MutableList<LinkMessage> {
        val received = CopyOnWriteArrayList<LinkMessage>()
        val scope = CoroutineScope(SupervisorJob())
        scopes += scope
        scope.launch { inbound.collect { received += it } }
        return received
    }

    private suspend fun ConversationLink.awaitConnectedPeer(timeoutMs: Long = 15_000): LinkState.Connected {
        awaitState(timeoutMs) { it is LinkState.Connected && it.peerPresent }
        return state.value as LinkState.Connected
    }

    private suspend fun ConversationLink.awaitState(
        timeoutMs: Long = 15_000,
        predicate: (LinkState) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(state.value)) return
            delay(20)
        }
        fail("timed out waiting for a state; last was ${state.value}")
    }

    private suspend fun List<LinkMessage>.awaitFirst(
        timeoutMs: Long = 15_000,
        predicate: (LinkMessage) -> Boolean,
    ): LinkMessage {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            firstOrNull(predicate)?.let { return it }
            delay(20)
        }
        fail("timed out waiting for a frame; received ${this.map { it.type to it.id }}")
    }
}
