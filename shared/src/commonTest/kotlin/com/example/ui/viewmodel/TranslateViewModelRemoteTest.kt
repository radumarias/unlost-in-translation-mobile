package com.example.ui.viewmodel

import com.example.link.ConversationLink
import com.example.link.LinkMessage
import com.example.link.SessionTicket
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
        assertEquals("Bună ziua", vm.partnerBText.value)
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
