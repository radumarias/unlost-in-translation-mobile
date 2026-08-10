package com.example.ui.viewmodel

import com.example.link.ConversationLink
import com.example.link.LinkMessage
import com.example.link.LinkState
import com.example.link.SessionTicket
import com.example.link.Verification
import com.example.model.Language
import com.example.model.TurnOrigin
import com.example.ui.viewmodel.fakes.FakeHistoryRepository
import com.example.ui.viewmodel.fakes.FakeTranslationApi
import com.example.ui.viewmodel.fakes.LoopbackConversationLink
import com.example.ui.viewmodel.fakes.RecordingBroadcaster
import com.example.ui.viewmodel.fakes.RecordingConversationLink
import com.example.ui.viewmodel.fakes.RecordingLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two-device conversation mode in the view model (issue #33 Phases 3–4). No socket
 * is involved: the link is a seam, so the whole feature is exercised through fakes
 * on both the JVM host and in ChromeHeadless.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslateViewModelRemoteTest {

    private lateinit var api: FakeTranslationApi
    private lateinit var repository: FakeHistoryRepository
    private lateinit var broadcaster: RecordingBroadcaster
    private lateinit var log: RecordingLog

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        api = FakeTranslationApi()
        repository = FakeHistoryRepository()
        broadcaster = RecordingBroadcaster()
        log = RecordingLog()
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(link: ConversationLink) =
        TranslateViewModel(api, repository, broadcaster, log, link = link)

    // --- hosting and joining ---

    @Test
    fun hostingPublishesATicketAndConnectsToIt() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)

        vm.startRemoteSession()

        val ticket = assertNotNull(vm.remoteTicket.value)
        assertEquals(SessionTicket.SESSION_ID_LENGTH, ticket.sessionId.length)
        assertEquals("en", ticket.hostLang)
        assertNull(ticket.manualCode, "the QR path must not weaken its id with a typed code")
        assertEquals(listOf(ticket), link.connects)
        assertTrue(vm.isRemoteSession.value)
    }

    @Test
    fun hostingWithATypedCodeShowsTheCodeAndUsesItsSession() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)

        vm.startRemoteSessionWithCode()

        val ticket = assertNotNull(vm.remoteTicket.value)
        val code = assertNotNull(ticket.manualCode)
        // Whatever the other phone types has to land in this exact session.
        assertEquals(ticket.sessionId, SessionTicket.forManualCode(code)?.sessionId)
    }

    @Test
    fun joiningAdoptsTheHostsLanguageAsTheTargetToSpeakBack() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link) // en -> es by default
        val host = SessionTicket(sessionId = SessionTicket.newSessionId(), hostLang = "ro")

        assertTrue(vm.joinRemoteSession(host.encodeCompact()))

        assertEquals("ro", vm.targetLanguage.value.code)
        // A guest has nothing to show: the QR belongs to the phone that is hosting.
        assertNull(vm.remoteTicket.value)
        assertEquals(1, link.connects.size)
    }

    @Test
    fun joiningSomethingThatIsNotATicketChangesNothing() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)

        assertFalse(vm.joinRemoteSession("have you seen my keys"))

        assertFalse(vm.isRemoteSession.value)
        assertTrue(link.connects.isEmpty())
    }

    @Test
    fun aHelloFromThePeerSetsTheLanguageThisPhoneTranslatesInto() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)
        vm.startRemoteSession()

        link.deliver(LinkMessage.hello(from = "peer", lang = "ro"))

        assertEquals("ro", vm.targetLanguage.value.code)
    }

    @Test
    fun aPeerSpeakingTheSameLanguageDoesNotCollapseBothPickers() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link) // source en
        vm.startRemoteSession()

        link.deliver(LinkMessage.hello(from = "peer", lang = "en"))

        // Adopting it would have swapped the pickers and changed what this user speaks.
        assertEquals("en", vm.sourceLanguage.value.code)
        assertEquals("es", vm.targetLanguage.value.code)
    }

    // --- turns ---

    @Test
    fun aConfirmedTurnGoesToThePeerAlreadyTranslated() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)
        vm.startRemoteSession()
        vm.prepareConversationPart("hello", isLanguageA = true)

        vm.confirmConversationDraft { _, _ -> }

        val sent = link.sent.single { it.type == LinkMessage.TYPE_TURN }
        assertEquals("hello", sent.original)
        assertEquals("T(hello)", sent.translated)
        assertEquals("en", sent.lang)
        assertEquals(vm.conversationHistory.value.single().id, sent.id)
    }

    @Test
    fun aTurnFromThePeerIsAppendedAndAttributedToTheOtherSide() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)
        vm.startRemoteSession()

        link.deliver(
            LinkMessage.turn(
                from = "peer",
                id = "t-1",
                lang = "ro",
                original = "Bună ziua",
                translated = "Good afternoon",
            )
        )

        val item = vm.conversationHistory.value.single()
        assertEquals("Bună ziua", item.text)
        assertEquals("Good afternoon", item.translated)
        assertEquals(TurnOrigin.REMOTE, item.origin)
        assertEquals("ro", item.srcLangCode)
        assertFalse(item.speakA)
    }

    @Test
    fun aReplayedTurnIsAppliedExactlyOnce() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)
        vm.startRemoteSession()
        val turn = LinkMessage.turn("peer", "t-1", "ro", "a", "b")

        link.deliver(turn)
        // The relay hands a reconnecting device the frames it missed, so this is a
        // normal event and not a fault.
        link.deliver(turn)

        assertEquals(1, vm.conversationHistory.value.size)
    }

    @Test
    fun aTurnThatCameFromThePeerIsNotSentBackToIt() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)
        vm.startRemoteSession()

        link.deliver(LinkMessage.turn("peer", "t-1", "ro", "a", "b"))

        assertTrue(link.sent.none { it.type == LinkMessage.TYPE_TURN })
    }

    // --- the budget: receiving a turn must cost nothing ---

    @Test
    fun receivingATurnCostsTheReceiverNoTranslationCalls() = runTest {
        // The rule this pins is HANDOFF.md #6: the free tier allows about twenty
        // requests per day per model, and nothing new may add a call to the
        // per-message path. The sender translates once and puts both halves on the
        // wire; the receiver renders what it was given.
        val (linkA, linkB) = LoopbackConversationLink.pair()

        val apiA = FakeTranslationApi()
        val vmA = TranslateViewModel(apiA, FakeHistoryRepository(), RecordingBroadcaster(), log, link = linkA)
        val apiB = FakeTranslationApi()
        val vmB = TranslateViewModel(apiB, FakeHistoryRepository(), RecordingBroadcaster(), log, link = linkB)

        vmB.setSourceLanguage(Language.getByCode("ro"))
        vmB.startRemoteSession()
        vmA.startRemoteSession()

        val callsOnBBeforeAnyoneSpoke = apiB.callCount()

        vmA.prepareConversationPart("hello", isLanguageA = true)
        vmA.confirmConversationDraft { _, _ -> }

        val item = vmB.conversationHistory.value.single()
        assertEquals("hello", item.text)
        assertEquals("T(hello)", item.translated)
        assertEquals(TurnOrigin.REMOTE, item.origin)
        assertEquals("en", item.srcLangCode)

        assertEquals(
            callsOnBBeforeAnyoneSpoke,
            apiB.callCount(),
            "receiving a turn must not cost the receiver a single API call",
        )
    }

    // --- ending a session ---

    @Test
    fun aByeFromThePeerEndsTheSession() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)
        vm.startRemoteSession()

        link.deliver(LinkMessage.bye(from = "peer"))

        assertFalse(vm.isRemoteSession.value)
        assertNull(vm.remoteTicket.value)
        assertEquals(1, link.disconnects)
    }

    @Test
    fun leavingConversationModeTellsThePeerBeforeWipingTheHistory() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)
        vm.setConversationMode(true)
        vm.startRemoteSession()

        vm.setConversationMode(false)

        assertEquals(1, link.disconnects)
        assertFalse(vm.isRemoteSession.value)
        assertTrue(vm.conversationHistory.value.isEmpty())
    }

    @Test
    fun aRemoteSessionAndDualHeadsetAreMutuallyExclusive() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)
        vm.setDualHeadsetModeEnabled(true)

        vm.startRemoteSession()

        // One phone with two headsets and two phones with one each are different
        // products; a remote turn has no local headset assignment to route to.
        assertFalse(vm.dualHeadsetState.value.isDualHeadsetModeEnabled)
    }

    @Test
    fun endingASessionTwiceIsHarmless() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)
        vm.startRemoteSession()

        vm.endRemoteSession()
        vm.endRemoteSession()

        assertEquals(1, link.disconnects)
    }

    @Test
    fun aFrameThatArrivesAfterLeavingIsIgnored() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)
        vm.startRemoteSession()
        vm.endRemoteSession()

        // The collector outlives the session on purpose, so the guard is what keeps
        // a frame still in flight when the user hit Leave from re-opening the
        // conversation on their screen.
        link.deliver(LinkMessage.turn("peer", "t-1", "ro", "a", "b"))

        assertTrue(vm.conversationHistory.value.isEmpty())
        assertFalse(vm.isRemoteSession.value)
    }

    @Test
    fun aSecondSessionStillReceivesTurns() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)
        vm.startRemoteSession()
        vm.endRemoteSession()

        vm.startRemoteSession()
        link.deliver(LinkMessage.turn("peer", "t-2", "ro", "a", "b"))

        // Subscribing once and never re-subscribing is what closes the window in
        // which a frame is emitted to nobody; this is the other half of that — the
        // one subscription has to still be there for session two.
        assertEquals(1, vm.conversationHistory.value.size)
    }

    @Test
    fun aLinkThatCannotConnectSaysSoInsteadOfCrashing() = runTest {
        val link = RecordingConversationLink()
        link.connectFailure = IllegalStateException("no relay here")
        val vm = viewModel(link)
        val seen = mutableListOf<String>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.messages.collect { seen += it }
        }

        vm.startRemoteSession()

        // Unhandled, this reaches Android's default handler and takes the app down.
        assertEquals(listOf(vm.strings.linkFailedNetwork), seen)
        collector.cancel()
    }

    // --- the SAS gate (issue #34 §5): nothing is rendered before the check ---

    @Test
    fun onTheTypedCodePathATurnIsHeldBackUntilTheCodeIsConfirmed() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)
        vm.startRemoteSession()
        link.setState(needsSasCheck())

        link.deliver(LinkMessage.turn("peer", "t-1", "ro", "Bună", "Hello"))

        // Held, not dropped: the peer may speak while the user is still reading the
        // digits, and losing what they said would be its own kind of wrong.
        assertTrue(vm.conversationHistory.value.isEmpty(), "nothing may render before the check")

        vm.confirmSasMatch()

        assertEquals("Bună", vm.conversationHistory.value.single().text)
    }

    @Test
    fun heldTurnsAreReleasedInTheOrderTheyArrived() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)
        vm.startRemoteSession()
        link.setState(needsSasCheck())

        link.deliver(LinkMessage.turn("peer", "t-1", "ro", "one", "one"))
        link.deliver(LinkMessage.turn("peer", "t-2", "ro", "two", "two"))
        vm.confirmSasMatch()

        assertEquals(listOf("one", "two"), vm.conversationHistory.value.map { it.text })
    }

    @Test
    fun sayingTheCodesDoNotMatchEndsTheSessionAndShowsNothing() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)
        vm.startRemoteSession()
        link.setState(needsSasCheck())
        link.deliver(LinkMessage.turn("peer", "t-1", "ro", "Bună", "Hello"))

        vm.rejectSasMatch()

        // Discarded rather than shown: it did not come from the person the user
        // thinks they are talking to.
        assertTrue(vm.conversationHistory.value.isEmpty())
        assertFalse(vm.isRemoteSession.value)
        assertEquals(1, link.disconnects)
    }

    @Test
    fun theQrPathNeverAsksAndNeverHoldsAnythingBack() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)
        vm.startRemoteSession()
        link.setState(
            LinkState.Connected(peerPresent = true, peerLang = "ro", verification = Verification.VERIFIED_BY_QR)
        )

        link.deliver(LinkMessage.turn("peer", "t-1", "ro", "Bună", "Hello"))

        // The QR already authenticated the other phone's key, so there is nothing to
        // ask the user and no reason to make them wait.
        assertEquals("Bună", vm.conversationHistory.value.single().text)
        assertFalse(vm.sasConfirmed.value)
    }

    @Test
    fun aNewSessionAsksAgain() = runTest {
        val link = RecordingConversationLink()
        val vm = viewModel(link)
        vm.startRemoteSession()
        link.setState(needsSasCheck())
        vm.confirmSasMatch()
        assertTrue(vm.sasConfirmed.value)

        vm.endRemoteSession()
        vm.startRemoteSession()

        // A confirmation belongs to the session it was given in; carrying it over
        // would silently skip the check on the next pairing.
        assertFalse(vm.sasConfirmed.value)
    }

    private fun needsSasCheck() = LinkState.Connected(
        peerPresent = true,
        peerLang = "ro",
        verification = Verification.NEEDS_SAS_CHECK,
        sas = "41908",
    )

    // --- the two latent defects two devices turn real (issue #33, findings #3 and #5) ---

    @Test
    fun historicalTurnsKeepTheLanguageTheyWereSpokenIn() = runTest {
        val vm = viewModel(RecordingConversationLink())
        vm.setAiEnhanced(true)
        vm.prepareConversationPart("we would like the fish", isLanguageA = true)
        vm.confirmConversationDraft { _, _ -> }

        // The user changes the target mid-conversation — or, with two devices, the
        // peer's `hello` does it for them.
        vm.setTargetLanguage(Language.getByCode("ro"))
        vm.prepareConversationPart("and the wine", isLanguageA = true)

        val context = vm.contextOfLastGeminiCall()
        assertEquals(
            listOf("English"),
            context.history.map { it.speakerLangName }.distinct(),
            "a turn already spoken must not be relabelled by a later picker change",
        )
    }

    @Test
    fun aValidationFromAnAbandonedDraftDoesNotLandOnTheNextOne() = runTest {
        val vm = viewModel(RecordingConversationLink())
        val slowValidation = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.onValidate = { _, _ ->
            slowValidation.await()
            com.example.network.ValidationResult(
                isWarning = true,
                explanation = "about the first draft",
                suggestions = emptyList(),
            )
        }

        vm.prepareConversationPart("first", isLanguageA = true)
        vm.cancelConversationDraft()
        // A second turn — from this phone or, now, from the peer — opens a new draft.
        api.onValidate = { _, _ ->
            com.example.network.ValidationResult(false, "about the second draft", emptyList())
        }
        vm.prepareConversationPart("second", isLanguageA = true)

        slowValidation.complete(Unit)

        val state = vm.conversationDraft.value?.validationState
        val success = state as? ValidationState.Success
        assertEquals("about the second draft", success?.explanation)
    }

    private fun TranslateViewModel.contextOfLastGeminiCall() = api.geminiContexts.last()

    private fun FakeTranslationApi.callCount(): Int =
        googleCalls.size + geminiCalls.size + validateCalls.size + detectCalls.size
}
