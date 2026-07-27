package com.example.ui.viewmodel

import com.example.model.ContextTurn
import com.example.model.Language
import com.example.model.Situation
import com.example.model.Tone
import com.example.network.ValidationResult
import com.example.ui.viewmodel.fakes.FakeHistoryRepository
import com.example.ui.viewmodel.fakes.FakeTranslationApi
import com.example.ui.viewmodel.fakes.RecordingBroadcaster
import com.example.ui.viewmodel.fakes.RecordingLog
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavior tests for the shared [TranslateViewModel] (design §10): happy path,
 * language swap-on-conflict, round-trip staleness guard, conversation draft
 * confirm/cancel, plus the voice-result routing contract added in Phase 4.
 * Runs on both the JVM host and wasm (commonTest). The fakes never delay, so
 * an unconfined Main dispatcher lets every pipeline settle synchronously —
 * except where a test deliberately parks a call on a [CompletableDeferred].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslateViewModelTest {

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
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = TranslateViewModel(api, repository, broadcaster, log)

    // --- Main translate pipeline ---

    @Test
    fun happyPath_translates_persists_broadcasts_roundTrips_validates() = runTest {
        val vm = viewModel()
        vm.setSourceText("hello")

        vm.triggerTranslation()

        val state = assertIs<TranslationState.Success>(vm.translationState.value)
        assertEquals("hello", state.originalText)
        assertEquals("T(hello)", state.translatedText)
        // Round trip translates the translation back (target -> source).
        assertEquals("T(T(hello))", state.roundTripText)
        assertEquals(Triple("T(hello)", "es", "en"), api.googleCalls[1])

        // Persisted once, with the language pair and the "now" timestamp sentinel handled.
        assertEquals(1, repository.rows.size)
        assertEquals("hello", repository.rows.single().sourceText)
        assertEquals("en", repository.rows.single().sourceLangCode)
        assertEquals("es", repository.rows.single().targetLangCode)

        // Wear broadcast fired exactly once.
        assertEquals(1, broadcaster.broadcasts.size)
        assertEquals("T(hello)", broadcaster.broadcasts.single().translatedText)

        val validation = assertIs<ValidationState.Success>(vm.validationState.value)
        assertEquals("Looks fine.", validation.explanation)
    }

    @Test
    fun sanityCheckAndIdiom_reachTheValidationState() = runTest {
        api.onValidate = { _, _ ->
            ValidationResult(
                isWarning = true,
                explanation = "Too blunt.",
                suggestions = emptyList(),
                sanityCheck = "They will hear an order, not a request.",
                idiomExplanation = "\"Break a leg\" means good luck.",
            )
        }
        val vm = viewModel()
        vm.setSourceText("break a leg")

        vm.triggerTranslation()

        val validation = assertIs<ValidationState.Success>(vm.validationState.value)
        assertEquals("They will hear an order, not a request.", validation.sanityCheck)
        assertEquals("\"Break a leg\" means good luck.", validation.idiomExplanation)
    }

    // --- Tone / situation presets (ported from the web build) ---

    @Test
    fun defaultContext_keepsTheGooglePath_andSendsNoConstraints() = runTest {
        val vm = viewModel()
        vm.setSourceText("hello")

        vm.triggerTranslation()

        assertTrue(api.geminiCalls.isEmpty(), "default context must not force the Gemini path")
        assertEquals(Tone.AUTO, api.validateContexts.single().tone)
        assertEquals(Situation.GENERAL, api.validateContexts.single().situation)
    }

    @Test
    fun settingATone_turnsOnAiEnhanced_andRetranslatesThroughGemini() = runTest {
        val vm = viewModel()
        vm.setSourceText("hello")
        vm.triggerTranslation()
        assertFalse(vm.isAiEnhanced.value)

        vm.setTone("Formal")

        // Google cannot express a tone, so the toggle is flipped rather than
        // the selection being silently ignored.
        assertTrue(vm.isAiEnhanced.value)
        assertEquals("Formal", vm.tone.value)
        assertEquals("G(hello)", assertIs<TranslationState.Success>(vm.translationState.value).translatedText)
        assertEquals("Formal", api.geminiContexts.last().tone)
    }

    @Test
    fun situationReachesBothTheTranslationAndTheValidationCall() = runTest {
        val vm = viewModel()
        vm.setSourceText("hello")

        vm.setSituation("Medical")

        assertEquals("Medical", api.geminiContexts.last().situation)
        assertEquals("Medical", api.validateContexts.last().situation)
    }

    @Test
    fun reselectingTheSameToneIsANoOp() = runTest {
        val vm = viewModel()
        vm.setSourceText("hello")
        vm.triggerTranslation()
        val callsBefore = api.googleCalls.size

        vm.setTone(Tone.AUTO)

        assertFalse(vm.isAiEnhanced.value)
        assertEquals(callsBefore, api.googleCalls.size)
    }

    @Test
    fun aToneSelectedWhileAiEnhancedIsOffStillForcesGemini() = runTest {
        val vm = viewModel()
        vm.setTone("Playful")
        // The user turns the toggle back off; the tone constraint must still win.
        vm.setAiEnhanced(false)
        vm.setSourceText("hello")

        vm.triggerTranslation()

        assertEquals("G(hello)", assertIs<TranslationState.Success>(vm.translationState.value).translatedText)
    }

    @Test
    fun blankText_resetsToIdle_withoutApiCalls() = runTest {
        val vm = viewModel()
        vm.setSourceText("   ")

        vm.triggerTranslation()

        assertEquals(TranslationState.Idle, vm.translationState.value)
        assertEquals(ValidationState.Idle, vm.validationState.value)
        assertTrue(api.googleCalls.isEmpty())
    }

    @Test
    fun primaryFailure_surfacesErrorState() = runTest {
        api.onGoogle = { _, _, _ -> throw Exception("boom") }
        val vm = viewModel()
        vm.setSourceText("hello")

        vm.triggerTranslation()

        assertEquals(TranslationState.Error("boom"), vm.translationState.value)
        assertTrue(repository.rows.isEmpty())
        assertTrue(broadcaster.broadcasts.isEmpty())
    }

    @Test
    fun aiEnhancedToggle_reroutesToGemini() = runTest {
        val vm = viewModel()
        vm.setSourceText("hello")

        vm.setAiEnhanced(true) // re-triggers translation because text is present

        val state = assertIs<TranslationState.Success>(vm.translationState.value)
        assertEquals("G(hello)", state.translatedText)
        assertEquals(1, api.geminiCalls.size)
        // Round trip always goes through Google, per the pipeline.
        assertEquals("G(hello)", api.googleCalls.single().first)
    }

    // --- Language selection ---

    @Test
    fun settingSourceEqualToTarget_swapsInsteadOfDuplicating() = runTest {
        val vm = viewModel() // en -> es
        vm.setSourceLanguage(Language.getByCode("es"))

        assertEquals("es", vm.sourceLanguage.value.code)
        assertEquals("en", vm.targetLanguage.value.code)
    }

    @Test
    fun settingTargetEqualToSource_swapsInsteadOfDuplicating() = runTest {
        // Symmetric guard: the fixed defect existed verbatim in both setters.
        val vm = viewModel() // en -> es
        vm.setTargetLanguage(Language.getByCode("en"))

        assertEquals("es", vm.sourceLanguage.value.code)
        assertEquals("en", vm.targetLanguage.value.code)
    }

    @Test
    fun settingUnrelatedLanguages_assignsWithoutSwapping() = runTest {
        val vm = viewModel() // en -> es
        vm.setSourceLanguage(Language.getByCode("fr"))
        vm.setTargetLanguage(Language.getByCode("de"))

        assertEquals("fr", vm.sourceLanguage.value.code)
        assertEquals("de", vm.targetLanguage.value.code)
    }

    @Test
    fun conflictSwapViaSetter_carriesSwapLanguagesTextSideEffect() = runTest {
        // The conflict branch must go through swapLanguages(), which also
        // promotes the previous translation to the new source text.
        val vm = viewModel()
        vm.setSourceText("hello")
        vm.triggerTranslation() // Success(hello, T(hello))

        vm.setSourceLanguage(Language.getByCode("es"))

        assertEquals("T(hello)", vm.sourceText.value)
    }

    @Test
    fun swapAfterSuccess_reusesTranslationAsNewSourceText() = runTest {
        val vm = viewModel()
        vm.setSourceText("hello")
        vm.triggerTranslation()

        vm.swapLanguages()

        assertEquals("es", vm.sourceLanguage.value.code)
        assertEquals("T(hello)", vm.sourceText.value)
        val state = assertIs<TranslationState.Success>(vm.translationState.value)
        assertEquals("T(T(hello))", state.translatedText)
    }

    // --- Round-trip staleness guard ---

    @Test
    fun staleRoundTrip_doesNotOverwriteANewerTranslation() = runTest {
        // Round-trip calls (inputs already of the form "T(...)") park on a gate;
        // primary calls return immediately.
        val gate = CompletableDeferred<String>()
        api.onGoogle = { text, _, _ ->
            if (text.startsWith("T(")) gate.await() else "T($text)"
        }
        val vm = viewModel()

        vm.setSourceText("one")
        vm.triggerTranslation() // Success(one, T(one)); its round trip parks on the gate
        vm.setSourceText("two")
        vm.triggerTranslation() // Success(two, T(two)); its round trip parks too

        gate.complete("RT") // releases both parked round trips

        val state = assertIs<TranslationState.Success>(vm.translationState.value)
        assertEquals("two", state.originalText)
        assertEquals("T(two)", state.translatedText)
        // The newer translation's round trip landed; the stale one was discarded
        // by the translatedText == translated guard.
        assertEquals("RT", state.roundTripText)
    }

    @Test
    fun staleValidation_doesNotOverwriteANewerTranslationsValidation() = runTest {
        // Symmetric to the round-trip guard above: the first translation's slow
        // validation must not land on the second translation's card. Validation
        // of the first ("one") parks on a gate; the second returns immediately.
        val gate = CompletableDeferred<Unit>()
        api.onValidate = { original, _ ->
            if (original == "one") {
                gate.await()
                ValidationResult(isWarning = true, explanation = "stale one", suggestions = emptyList())
            } else {
                ValidationResult(isWarning = false, explanation = "fresh two", suggestions = emptyList())
            }
        }
        val vm = viewModel()

        vm.setSourceText("one")
        vm.triggerTranslation() // its validation parks on the gate
        vm.setSourceText("two")
        vm.triggerTranslation() // validates immediately -> "fresh two"

        gate.complete(Unit) // release the stale validation

        val validation = assertIs<ValidationState.Success>(vm.validationState.value)
        assertEquals("fresh two", validation.explanation)
        assertFalse(validation.isWarning, "the stale validation must not have landed")
    }

    // --- Conversation drafts ---

    @Test
    fun conversationDraft_confirm_appendsHistoryAndNotifiesCaller() = runTest {
        val vm = viewModel()
        vm.prepareConversationPart("hi", isLanguageA = true)

        val draft = vm.conversationDraft.value
        assertEquals("T(hi)", draft?.translatedText)
        assertEquals(false, draft?.isTranslating)
        assertEquals("T(T(hi))", draft?.roundTripText)
        assertIs<ValidationState.Success>(draft?.validationState)

        var spoken: Pair<String, Boolean>? = null
        vm.confirmConversationDraft { text, isA -> spoken = text to isA }

        assertNull(vm.conversationDraft.value)
        assertEquals("T(hi)" to true, spoken)
        val item = vm.conversationHistory.value.single()
        assertEquals("hi", item.text)
        assertEquals("T(hi)", item.translated)
        assertTrue(item.speakA)
    }

    // --- Conversation context fed into the prompt ---

    @Test
    fun conversationTurns_areSentAsPromptContext_labelledByTheirSpeakersLanguage() = runTest {
        api.onGoogle = { text, _, _ -> "T($text)" }
        val vm = viewModel() // en -> es
        vm.setAiEnhanced(true)

        vm.prepareConversationPart("we would like the fish", isLanguageA = true)
        vm.confirmConversationDraft { _, _ -> }
        vm.prepareConversationPart("para cuantas personas?", isLanguageA = false)
        vm.confirmConversationDraft { _, _ -> }
        vm.prepareConversationPart("four", isLanguageA = true)

        // The third turn carries the first two, each tagged with the language
        // its speaker actually spoke.
        val history = api.geminiContexts.last().history
        assertEquals(2, history.size)
        assertEquals(ContextTurn("English", "we would like the fish"), history[0])
        assertEquals(ContextTurn("Spanish", "para cuantas personas?"), history[1])
    }

    @Test
    fun conversationContext_isCappedAtTheMostRecentTurns() = runTest {
        val vm = viewModel()
        vm.setAiEnhanced(true)
        repeat(TranslateViewModel.CONVERSATION_CONTEXT_TURNS + 1) { i ->
            vm.prepareConversationPart("turn $i", isLanguageA = true)
            vm.confirmConversationDraft { _, _ -> }
        }

        vm.prepareConversationPart("latest", isLanguageA = true)

        val history = api.geminiContexts.last().history
        assertEquals(TranslateViewModel.CONVERSATION_CONTEXT_TURNS, history.size)
        // The oldest turn has fallen out of the window; the newest is kept.
        assertEquals("turn 1", history.first().text)
        assertEquals("turn ${TranslateViewModel.CONVERSATION_CONTEXT_TURNS}", history.last().text)
    }

    @Test
    fun translateTab_sendsNoConversationHistory() = runTest {
        val vm = viewModel()
        vm.setAiEnhanced(true)
        vm.prepareConversationPart("hi", isLanguageA = true)
        vm.confirmConversationDraft { _, _ -> }

        vm.setSourceText("unrelated")
        vm.triggerTranslation()

        // The tab has no session — the persisted log must not leak into the prompt.
        assertTrue(api.geminiContexts.last().history.isEmpty())
    }

    @Test
    fun conversationDraft_cancel_discardsEverything() = runTest {
        val vm = viewModel()
        vm.prepareConversationPart("hi", isLanguageA = false)

        vm.cancelConversationDraft()

        assertNull(vm.conversationDraft.value)
        assertTrue(vm.conversationHistory.value.isEmpty())
    }

    @Test
    fun conversationDraft_translationFailure_reportsErrorInDraft() = runTest {
        api.onGoogle = { _, _, _ -> throw Exception("offline") }
        val vm = viewModel()

        vm.prepareConversationPart("hi", isLanguageA = true)

        val draft = vm.conversationDraft.value
        assertEquals(false, draft?.isTranslating)
        assertEquals(ValidationState.Error("offline"), draft?.validationState)
    }

    @Test
    fun conversationPartyB_translatesTargetToSource() = runTest {
        val vm = viewModel() // en -> es
        vm.prepareConversationPart("hola", isLanguageA = false)

        // Party B speaks the target language (es) toward the source (en).
        assertEquals(Triple("hola", "es", "en"), api.googleCalls.first())
    }

    // --- Voice-result routing contract (Phase 4, shared by both platforms) ---

    @Test
    fun onVoiceInputResult_ignoresNullBlankAndWhitespace() = runTest {
        val vm = viewModel()

        vm.onVoiceInputResult(null, isConversationA = null)
        vm.onVoiceInputResult("", isConversationA = null)
        vm.onVoiceInputResult("   ", isConversationA = true)

        assertEquals(TranslationState.Idle, vm.translationState.value)
        assertNull(vm.conversationDraft.value)
        assertTrue(api.googleCalls.isEmpty())
        assertEquals("", vm.sourceText.value)
    }

    @Test
    fun onVoiceInputResult_mainMic_setsTextAndTranslates() = runTest {
        val vm = viewModel()

        vm.onVoiceInputResult("hello world", isConversationA = null)

        assertEquals("hello world", vm.sourceText.value)
        val state = assertIs<TranslationState.Success>(vm.translationState.value)
        assertEquals("T(hello world)", state.translatedText)
    }

    @Test
    fun onVoiceInputResult_conversation_feedsDraftForTheRightParty() = runTest {
        val vm = viewModel()

        vm.onVoiceInputResult("hola", isConversationA = false)

        val draft = vm.conversationDraft.value
        assertEquals("hola", draft?.sourceText)
        assertEquals(false, draft?.isLanguageA)
    }
}
