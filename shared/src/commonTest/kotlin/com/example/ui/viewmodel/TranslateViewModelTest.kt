package com.example.ui.viewmodel

import com.example.model.ContextTurn
import com.example.model.Language
import com.example.model.Situation
import com.example.model.Tone
import com.example.network.DetectedTranslation
import com.example.network.ImageTranslationResult
import com.example.network.KtorTranslationApi
import com.example.network.UserVisibleException
import com.example.network.ValidationResult
import com.example.ui.i18n.Strings
import com.example.ui.viewmodel.fakes.FakeHistoryRepository
import com.example.ui.viewmodel.fakes.FakeTranslationApi
import com.example.ui.viewmodel.fakes.RecordingBroadcaster
import com.example.ui.viewmodel.fakes.RecordingLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.SerializationException
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

    // --- Image OCR translation ---

    @Test
    fun imageTranslation_fillsTheInput_andRunsTheOrdinaryPipeline() = runTest {
        val vm = viewModel() // en -> es

        vm.onImageInputResult("QkFTRTY0", "image/jpeg")

        // A foreign menu comes back to the user's own language, so French was
        // read and English is the target.
        assertEquals("Plat du jour", vm.sourceText.value)
        val state = assertIs<TranslationState.Success>(vm.translationState.value)
        assertEquals("Dish of the day", state.translatedText)
        // Same round trip, history row, broadcast and validation a typed
        // translation gets.
        assertEquals(Triple("Dish of the day", "en", "fr"), api.googleCalls.single())
        assertEquals("fr", repository.rows.single().sourceLangCode)
        assertEquals(1, broadcaster.broadcasts.size)
        assertIs<ValidationState.Success>(vm.validationState.value)
        assertFalse(vm.isReadingImage.value)
    }

    @Test
    fun imageInTheUsersOwnLanguage_goesOutToTheOtherParty() = runTest {
        api.onImage = { _ -> ImageTranslationResult("en", "No entry", "Prohibido el paso") }
        val vm = viewModel() // en -> es

        vm.onImageInputResult("QkFTRTY0", "image/jpeg")

        // Read English, so the target is the other pill, not back to English.
        assertEquals(Triple("Prohibido el paso", "es", "en"), api.googleCalls.single())
    }

    @Test
    fun imageTranslation_withAnAutoSource_reportsWhatItRead() = runTest {
        val vm = viewModel()
        vm.setSourceLanguage(Language.autoDetect)

        vm.onImageInputResult("QkFTRTY0", "image/jpeg")

        assertEquals("true", api.imageCalls.single()[3])
        assertEquals("fr", vm.detectedSourceLanguage.value?.code)
    }

    @Test
    fun anUnreadableImage_surfacesAnError_ratherThanAnEmptyCard() = runTest {
        api.onImage = { _ -> throw UserVisibleException("Gemini found no text to translate in the image.") }
        val vm = viewModel()

        vm.onImageInputResult("QkFTRTY0", "image/jpeg")

        assertEquals(
            TranslationState.Error("Gemini found no text to translate in the image."),
            vm.translationState.value,
        )
        assertEquals(ValidationState.Idle, vm.validationState.value)
        assertFalse(vm.isReadingImage.value)
        assertTrue(repository.rows.isEmpty())
    }

    @Test
    fun aCancelledPick_doesNothing() = runTest {
        val vm = viewModel()

        vm.onImageInputResult(null, "")

        assertTrue(api.imageCalls.isEmpty())
        assertEquals(TranslationState.Idle, vm.translationState.value)
    }

    // --- Auto-detect source language ---

    @Test
    fun autoDetect_usesTheFreeGoogleEndpoint_andReportsWhatItFound() = runTest {
        val vm = viewModel()
        vm.setSourceLanguage(Language.autoDetect)
        vm.setSourceText("bonjour")

        vm.triggerTranslation()

        // Detection rides on sl=auto, so it costs no Gemini request.
        assertEquals(Triple("bonjour", "es", false), api.detectCalls.single())
        assertTrue(api.geminiCalls.isEmpty())
        assertEquals("fr", vm.detectedSourceLanguage.value?.code)
        // The selection stays on Auto-detect rather than being overwritten.
        assertEquals(Language.AUTO_CODE, vm.sourceLanguage.value.code)
    }

    @Test
    fun autoDetect_roundTripsAndPersistsAgainstTheDetectedLanguage() = runTest {
        val vm = viewModel()
        vm.setSourceLanguage(Language.autoDetect)
        vm.setSourceText("bonjour")

        vm.triggerTranslation()

        // Round trip goes back to French, not to the "auto" sentinel.
        assertEquals(Triple("D(bonjour)", "es", "fr"), api.googleCalls.single())
        assertEquals("fr", repository.rows.single().sourceLangCode)
        assertEquals("French", repository.rows.single().sourceLangName)
        assertEquals("fr", broadcaster.broadcasts.single().sourceLang.code)
    }

    @Test
    fun anUnsupportedDetectedLanguage_skipsTheRoundTrip_ratherThanGuessing() = runTest {
        api.onDetect = { text, _ -> DetectedTranslation("D($text)", "sv") } // not one of the 16
        val vm = viewModel()
        vm.setSourceLanguage(Language.autoDetect)
        vm.setSourceText("hej")

        vm.triggerTranslation()

        assertNull(vm.detectedSourceLanguage.value)
        assertTrue(api.googleCalls.isEmpty(), "no language to translate back to")
        assertEquals(Language.AUTO_CODE, repository.rows.single().sourceLangCode)
        // The validation prompt still gets the detected code rather than the
        // useless "Auto-detect".
        assertEquals("sv", api.validateLangNames.single().first)
    }

    @Test
    fun autoDetect_withAiEnhanced_routesToTheDetectingGeminiCall() = runTest {
        val vm = viewModel()
        vm.setAiEnhanced(true)
        vm.setSourceLanguage(Language.autoDetect)
        vm.setSourceText("bonjour")

        vm.triggerTranslation()

        assertEquals(Triple("bonjour", "Spanish", true), api.detectCalls.single())
    }

    @Test
    fun swappingAnUndetectedAutoSource_refusesInsteadOfMakingAutoTheTarget() = runTest {
        val vm = viewModel()
        vm.setSourceLanguage(Language.autoDetect)

        vm.swapLanguages()

        assertEquals(Language.AUTO_CODE, vm.sourceLanguage.value.code)
        assertEquals("es", vm.targetLanguage.value.code)
    }

    @Test
    fun swappingAfterDetection_resolvesAutoToWhatWasFound() = runTest {
        val vm = viewModel()
        vm.setSourceLanguage(Language.autoDetect)
        vm.setSourceText("bonjour")
        vm.triggerTranslation()

        vm.swapLanguages()

        assertEquals("es", vm.sourceLanguage.value.code)
        assertEquals("fr", vm.targetLanguage.value.code)
        assertNull(vm.detectedSourceLanguage.value)
    }

    @Test
    fun conversationMode_needsARealSourceLanguage() = runTest {
        val vm = viewModel()
        vm.setSourceLanguage(Language.autoDetect)

        vm.setConversationMode(true)

        assertFalse(vm.isConversationMode.value)
    }

    @Test
    fun changingTheSourceLanguage_clearsAStaleDetection() = runTest {
        val vm = viewModel()
        vm.setSourceLanguage(Language.autoDetect)
        vm.setSourceText("bonjour")
        vm.triggerTranslation()
        assertEquals("fr", vm.detectedSourceLanguage.value?.code)

        vm.setSourceLanguage(Language.getByCode("de"))

        assertNull(vm.detectedSourceLanguage.value)
    }

    // --- Rewrite (on demand) ---

    private fun warningValidation() {
        api.onValidate = { _, _ ->
            ValidationResult(
                isWarning = true,
                explanation = "Reads as a demand.",
                suggestions = emptyList(),
                idiomExplanation = "\"Break a leg\" means good luck.",
            )
        }
    }

    @Test
    fun rewrite_isNeverAutomatic() = runTest {
        warningValidation()
        val vm = viewModel()
        vm.setSourceText("break a leg")

        vm.triggerTranslation()

        // The web build fires this on every warning; on the free tier that is
        // most of a day's allowance spent unasked.
        assertTrue(api.rewriteCalls.isEmpty())
        assertEquals(RewriteState.Idle, vm.rewriteState.value)
    }

    @Test
    fun requestRewrite_sendsTheWarningAndTheIdiom_andOffersAlternatives() = runTest {
        warningValidation()
        val vm = viewModel()
        vm.setSourceText("break a leg")
        vm.triggerTranslation()

        vm.requestRewrite()

        val (text, warning, direction) = api.rewriteCalls.single()
        assertEquals("break a leg", text)
        assertTrue(warning.contains("Reads as a demand."), warning)
        assertTrue(warning.contains("good luck"), warning)
        assertNull(direction)

        val rewrite = assertIs<RewriteState.Success>(vm.rewriteState.value)
        assertEquals("R(break a leg)", rewrite.rewrittenSource)
        assertEquals(listOf("More formal", "Adapt to local culture"), rewrite.alternativeDirections)
    }

    @Test
    fun directedRewrite_keepsTheAlternativesItWasChosenFrom() = runTest {
        warningValidation()
        val vm = viewModel()
        vm.setSourceText("break a leg")
        vm.triggerTranslation()
        vm.requestRewrite()

        vm.requestRewrite("More formal")

        // The directed call answers with the text alone; the alternatives on
        // screen must survive so another can be tried.
        val rewrite = assertIs<RewriteState.Success>(vm.rewriteState.value)
        assertEquals("More formal", rewrite.direction)
        assertEquals(listOf("More formal", "Adapt to local culture"), rewrite.alternativeDirections)
        assertNull(rewrite.reloadingDirection)
        assertEquals("More formal", api.rewriteCalls.last().third)
    }

    @Test
    fun applyRewrite_putsItInTheInputAndTranslates() = runTest {
        warningValidation()
        val vm = viewModel()
        vm.setSourceText("break a leg")
        vm.triggerTranslation()
        vm.requestRewrite()

        vm.applyRewrite("good luck out there")

        assertEquals("good luck out there", vm.sourceText.value)
        assertEquals(
            "T(good luck out there)",
            assertIs<TranslationState.Success>(vm.translationState.value).translatedText,
        )
        // The rewrite belonged to the previous message.
        assertEquals(RewriteState.Idle, vm.rewriteState.value)
    }

    @Test
    fun aNewTranslation_dropsTheRewriteFromThePreviousMessage() = runTest {
        warningValidation()
        val vm = viewModel()
        vm.setSourceText("break a leg")
        vm.triggerTranslation()
        vm.requestRewrite()
        assertIs<RewriteState.Success>(vm.rewriteState.value)

        vm.setSourceText("something else")
        vm.triggerTranslation()

        assertEquals(RewriteState.Idle, vm.rewriteState.value)
    }

    @Test
    fun requestRewrite_withoutAValidatedTranslation_doesNothing() = runTest {
        val vm = viewModel()

        vm.requestRewrite()

        assertTrue(api.rewriteCalls.isEmpty())
        assertEquals(RewriteState.Idle, vm.rewriteState.value)
    }

    @Test
    fun rewriteFailure_surfacesAnErrorState() = runTest {
        warningValidation()
        api.onRewrite = { _, _, _ -> throw Exception("quota gone") }
        val vm = viewModel()
        vm.setSourceText("break a leg")
        vm.triggerTranslation()

        vm.requestRewrite()

        assertEquals(RewriteState.Error("quota gone"), vm.rewriteState.value)
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
        // "boom" is a log line, not a sentence, so the card shows the app's own
        // wording and the exception goes to the log.
        api.onGoogle = { _, _, _ -> throw Exception("boom") }
        val vm = viewModel()
        vm.setSourceText("hello")

        vm.triggerTranslation()

        assertEquals(TranslationState.Error(vm.strings.translationFailed), vm.translationState.value)
        assertTrue(repository.rows.isEmpty())
        assertTrue(broadcaster.broadcasts.isEmpty())
        assertTrue(log.errorThrowables.any { it?.message == "boom" }, "the detail must reach the log")
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
        // A plain Exception's message was written for a log, not a person, so the
        // draft shows the app's own sentence instead.
        api.onGoogle = { _, _, _ -> throw Exception("offline") }
        val vm = viewModel()

        vm.prepareConversationPart("hi", isLanguageA = true)

        val draft = vm.conversationDraft.value
        assertEquals(false, draft?.isTranslating)
        assertEquals(ValidationState.Error(vm.strings.conversationTranslationFailed), draft?.validationState)
    }

    @Test
    fun conversationDraft_showsAMessageThatWasWrittenForAUser() = runTest {
        api.onGoogle = { _, _, _ -> throw UserVisibleException("Gemini is over its quota for today.") }
        val vm = viewModel()

        vm.prepareConversationPart("hi", isLanguageA = true)

        assertEquals(
            ValidationState.Error("Gemini is over its quota for today."),
            vm.conversationDraft.value?.validationState,
        )
    }

    @Test
    fun aParserFailureNeverReachesTheScreen() = runTest {
        // The defect this pins, seen in a browser on 2026-08-10: a malformed
        // validation payload put `Unexpected JSON token at offset 758: Expected
        // end of the object or comma at path: $` plus a slice of the JSON in front
        // of the user, inside the Confirm Translation dialog.
        val leak = "Unexpected JSON token at offset 758: Expected end of the object or comma at path: \$"
        api.onValidate = { _, _ -> throw SerializationException(leak) }
        val vm = viewModel()

        vm.setSourceText("hello")
        vm.triggerTranslation()
        advanceUntilIdle()

        val state = assertIs<ValidationState.Error>(vm.validationState.value)
        assertEquals(vm.strings.validationUnavailable, state.message)
        assertFalse("offset" in state.message, "the parser's words must not be on screen: ${state.message}")
    }

    @Test
    fun theQuotaExplanationIsStillShownBare() = runTest {
        // It already reads as a plain sentence; the "Validation failed: …" prefix
        // would only muddy it.
        api.onValidate = { _, _ -> throw UserVisibleException(KtorTranslationApi.GEMINI_QUOTA_MESSAGE) }
        val vm = viewModel()

        vm.setSourceText("hello")
        vm.triggerTranslation()
        advanceUntilIdle()

        val state = assertIs<ValidationState.Error>(vm.validationState.value)
        assertEquals(KtorTranslationApi.GEMINI_QUOTA_MESSAGE, state.message)
    }

    @Test
    fun aMessageSaidBeforeAnyoneIsListeningIsStillDelivered() = runTest {
        // The view model outlives the composition, so there are real gaps with no
        // collector. `tryEmit` on a no-replay SharedFlow dropped those on the
        // floor; the snackbar simply never appeared.
        val vm = viewModel()
        vm.showMessage("said to nobody")

        val seen = mutableListOf<String>()
        val collector = launch { vm.messages.collect { seen += it } }
        advanceUntilIdle()
        collector.cancel()

        assertEquals(listOf("said to nobody"), seen)
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

    // --- Hand-over for the reply ---

    @Test
    fun handOver_swapsTheLanguagesAndClearsTheWorkspace() = runTest {
        val vm = viewModel()
        vm.setSourceText("hello")
        vm.triggerTranslation()
        assertIs<TranslationState.Success>(vm.translationState.value)

        vm.handOverForReply()

        assertEquals("es", vm.sourceLanguage.value.code)
        assertEquals("en", vm.targetLanguage.value.code)
        assertEquals("", vm.sourceText.value)
        assertEquals(TranslationState.Idle, vm.translationState.value)
        assertEquals(ValidationState.Idle, vm.validationState.value)
        assertEquals(RewriteState.Idle, vm.rewriteState.value)
    }

    @Test
    fun handOver_costsNoCall_unlikeSwapping() = runTest {
        val vm = viewModel()
        vm.setSourceText("hello")
        vm.triggerTranslation()
        val callsAfterTranslating = api.googleCalls.size

        vm.handOverForReply()

        // swapLanguages carries the translation back into the input and
        // re-translates; handing over starts a blank turn instead.
        assertEquals(callsAfterTranslating, api.googleCalls.size)
    }

    @Test
    fun handOver_resolvesAutoDetectToWhatWasFound() = runTest {
        val vm = viewModel()
        api.onDetect = { _, _ -> DetectedTranslation("T(bonjour)", "fr") }
        vm.setSourceLanguage(Language.autoDetect)
        vm.setSourceText("bonjour")
        vm.triggerTranslation()
        assertEquals("fr", vm.detectedSourceLanguage.value?.code)

        vm.handOverForReply()

        // The detected French becomes the target for the reply; Auto-detect
        // itself must never end up there — it has no BCP-47 tag.
        assertEquals("es", vm.sourceLanguage.value.code)
        assertEquals("fr", vm.targetLanguage.value.code)
        assertNull(vm.detectedSourceLanguage.value)
    }

    @Test
    fun handOver_refusesWhileAutoDetectHasFoundNothing() = runTest {
        val vm = viewModel()
        vm.setSourceLanguage(Language.autoDetect)
        val seen = mutableListOf<String>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.messages.collect { seen += it }
        }

        vm.handOverForReply()

        assertEquals(Language.AUTO_CODE, vm.sourceLanguage.value.code)
        assertEquals("es", vm.targetLanguage.value.code)
        assertEquals(listOf(Strings().translateFirstToDetect), seen)
        collector.cancel()
    }

    // --- Localised messages ---

    @Test
    fun snackbarMessages_comeFromTheInjectedCatalogue() = runTest {
        val vm = TranslateViewModel(
            api, repository, broadcaster, log,
            strings = Strings(aiEnhancedTurnedOn = "Ton activat"),
        )
        val seen = mutableListOf<String>()
        // Unconfined so the collector is subscribed before the emit — `messages`
        // is a tryEmit SharedFlow with no replay, so a late collector sees nothing.
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.messages.collect { seen += it }
        }

        vm.setTone("Formal")

        assertEquals(listOf("Ton activat"), seen)
        collector.cancel()
    }

    @Test
    fun errorFallbacks_comeFromTheCatalogue_whenTheExceptionHasNoMessage() = runTest {
        val vm = TranslateViewModel(
            api, repository, broadcaster, log,
            strings = Strings(translationFailed = "Traducere eșuată"),
        )
        // An exception with a null message is what the fallback exists for.
        api.onGoogle = { _, _, _ -> throw RuntimeException() }
        vm.setSourceText("hello")

        vm.triggerTranslation()

        assertEquals("Traducere eșuată", assertIs<TranslationState.Error>(vm.translationState.value).message)
    }

    @Test
    fun theVoicePrompt_isBuiltFromTheCatalogue_andNamesTheLanguage() {
        val vm = TranslateViewModel(
            api, repository, broadcaster, log,
            strings = Strings(voiceInputPrompt = "Vorbiți acum ({0})"),
        )

        assertEquals("Vorbiți acum (French)", vm.voiceInputPrompt(Language.getByCode("fr")))
    }

    @Test
    fun theDefaultCatalogueKeepsTheEnglishWording() {
        val vm = viewModel()

        assertEquals("Speak now (Spanish)...", vm.voiceInputPrompt(Language.getByCode("es")))
        assertEquals("Voice input is not supported on this device.", vm.strings.voiceInputUnsupported)
    }
}
