package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.TranslationHistoryRepository
import com.example.model.ContextTurn
import com.example.model.Language
import com.example.model.Situation
import com.example.model.Tone
import com.example.model.Translation
import com.example.model.TranslationContext
import com.example.network.KtorTranslationApi
import com.example.network.TranslationApi
import com.example.platform.AppLog
import com.example.ui.i18n.Strings
import com.example.ui.i18n.withArg
import com.example.wear.TranslationBroadcaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class TranslateViewModel(
    private val api: TranslationApi,
    private val repository: TranslationHistoryRepository,
    private val broadcaster: TranslationBroadcaster,
    private val log: AppLog,
    /**
     * The string catalogue for the messages this view model authors itself.
     * Public because the platform entry points need the same one for the text
     * they show outside the composition (the recognizer prompt, its failures).
     */
    val strings: Strings = Strings(),
) : ViewModel() {
    companion object {
        private const val TAG = "TranslateViewModel"

        /** How many prior conversation turns are sent as prompt context. */
        const val CONVERSATION_CONTEXT_TURNS = 10
    }

    /**
     * The "Speak now" voice prompt, built here so the platforms cannot drift:
     * Android shows it inside the RecognizerIntent dialog (EXTRA_PROMPT), web
     * surfaces it as a snackbar (no system dialog exists there).
     */
    fun voiceInputPrompt(language: Language): String =
        strings.voiceInputPrompt.withArg(language.name)

    /**
     * Routes a completed voice-recognition result identically on every platform
     * (design §8): a conversation request feeds the draft flow for the given
     * party; a main-mic request replaces the source text and translates. Null or
     * blank text (cancelled/empty recognition) is ignored.
     */
    fun onVoiceInputResult(spokenText: String?, isConversationA: Boolean?) {
        if (spokenText.isNullOrBlank()) return
        if (isConversationA != null) {
            prepareConversationPart(spokenText, isLanguageA = isConversationA)
        } else {
            setSourceText(spokenText)
            triggerTranslation()
        }
    }

    // One-shot user messages (voice-input status/errors) surfaced as snackbars by
    // the UI; replaces the Toast calls that used to live in the composable.
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    fun showMessage(message: String) {
        _messages.tryEmit(message)
    }

    // Text inputs and outputs
    private val _sourceText = MutableStateFlow("")
    val sourceText = _sourceText.asStateFlow()

    private val _translationState = MutableStateFlow<TranslationState>(TranslationState.Idle)
    val translationState = _translationState.asStateFlow()

    private val _validationState = MutableStateFlow<ValidationState>(ValidationState.Idle)
    val validationState = _validationState.asStateFlow()

    private val _rewriteState = MutableStateFlow<RewriteState>(RewriteState.Idle)
    val rewriteState = _rewriteState.asStateFlow()

    // Configured selected languages
    private val _sourceLanguage = MutableStateFlow(Language.getByCode("en"))
    val sourceLanguage = _sourceLanguage.asStateFlow()

    private val _targetLanguage = MutableStateFlow(Language.getByCode("es"))
    val targetLanguage = _targetLanguage.asStateFlow()

    /**
     * What auto-detect found, once a translation has run. Null while nothing
     * has been detected yet, or when the detected language is not one of the
     * sixteen this app lists.
     *
     * The selection itself stays on Auto-detect. The web build overwrites its
     * picker with the detected language; keeping it sticky is better for the
     * case auto-detect exists for — several messages in a row from someone
     * whose language you do not know.
     */
    private val _detectedSourceLanguage = MutableStateFlow<Language?>(null)
    val detectedSourceLanguage = _detectedSourceLanguage.asStateFlow()

    // Toggle for AI Enhanced (Gemini translation) vs standard Google Translate
    private val _isAiEnhanced = MutableStateFlow(false)
    val isAiEnhanced = _isAiEnhanced.asStateFlow()

    // Tone and situation presets (ported from the web build). Prompt-only: they
    // cost no extra requests, but they need a model that can read a prompt, so
    // selecting either forces the Gemini path (see [translationContext]).
    private val _tone = MutableStateFlow(Tone.AUTO)
    val tone = _tone.asStateFlow()

    private val _situation = MutableStateFlow(Situation.GENERAL)
    val situation = _situation.asStateFlow()

    fun setTone(tone: String) {
        if (_tone.value == tone) return
        _tone.value = tone
        onContextPresetChanged(enabledAi = tone != Tone.AUTO)
    }

    fun setSituation(situation: String) {
        if (_situation.value == situation) return
        _situation.value = situation
        onContextPresetChanged(enabledAi = situation != Situation.GENERAL)
    }

    /**
     * Google's free endpoint takes a bare string, so a tone or situation can
     * only be honoured by Gemini. Rather than silently ignoring the selection,
     * turn AI Enhanced on and say so — the toggle then shows the truth about
     * which backend is running.
     */
    private fun onContextPresetChanged(enabledAi: Boolean) {
        if (enabledAi && !_isAiEnhanced.value) {
            _isAiEnhanced.value = true
            showMessage(strings.aiEnhancedTurnedOn)
        }
        if (_sourceText.value.isNotBlank()) {
            triggerTranslation()
        }
    }

    /** The current prompt context; [ContextTurn]s are added by conversation mode. */
    private fun translationContext(history: List<ContextTurn> = emptyList()) =
        TranslationContext(tone = _tone.value, situation = _situation.value, history = history)

    /**
     * The conversation so far, as prompt context. Only the last
     * [CONVERSATION_CONTEXT_TURNS] are sent: a long exchange would otherwise
     * grow the prompt without bound, and the turns that disambiguate a pronoun
     * or a one-word answer are the recent ones.
     *
     * The Translate tab deliberately sends nothing. It has no session — its
     * `history` flow is the persisted log, so yesterday's unrelated rows would
     * be fed to the model as if they were this conversation.
     */
    private fun conversationTurns(): List<ContextTurn> =
        _conversationHistory.value.takeLast(CONVERSATION_CONTEXT_TURNS).map { item ->
            ContextTurn(
                speakerLangName = if (item.speakA) _sourceLanguage.value.name else _targetLanguage.value.name,
                text = item.text,
            )
        }

    // Database entries
    val history: StateFlow<List<Translation>> = repository.allTranslations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val starred: StateFlow<List<Translation>> = repository.starredTranslations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Conversation Mode States
    private val _isConversationMode = MutableStateFlow(false)
    val isConversationMode = _isConversationMode.asStateFlow()

    private val _conversationDraft = MutableStateFlow<ConversationDraft?>(null)
    val conversationDraft = _conversationDraft.asStateFlow()

    private val _dualHeadsetState = MutableStateFlow(DualHeadsetModel())
    val dualHeadsetState = _dualHeadsetState.asStateFlow()

    fun setDualHeadsetModeEnabled(enabled: Boolean) {
        _dualHeadsetState.value = _dualHeadsetState.value.copy(isDualHeadsetModeEnabled = enabled)
    }

    fun setHeadsetForA(deviceId: Int?) {
        _dualHeadsetState.value = _dualHeadsetState.value.copy(deviceA = deviceId)
    }

    fun setHeadsetForB(deviceId: Int?) {
        _dualHeadsetState.value = _dualHeadsetState.value.copy(deviceB = deviceId)
    }

    private val _partnerAText = MutableStateFlow("")
    val partnerAText = _partnerAText.asStateFlow()

    private val _partnerBText = MutableStateFlow("")
    val partnerBText = _partnerBText.asStateFlow()

    private val _conversationHistory = MutableStateFlow<List<ConversationItem>>(emptyList())
    val conversationHistory = _conversationHistory.asStateFlow()

    data class ConversationItem(
        val text: String,
        val translated: String,
        val speakA: Boolean, // if true, spoken by Language A. Else Language B.
        @OptIn(ExperimentalTime::class)
        val timestamp: Long = Clock.System.now().toEpochMilliseconds()
    )

    fun setSourceText(text: String) {
        _sourceText.value = text
        if (text.isBlank()) {
            _translationState.value = TranslationState.Idle
        }
    }

    fun setSourceLanguage(language: Language) {
        if (_sourceLanguage.value.code == language.code) return
        // Picking the current target as the new source is a swap request. Swap
        // BEFORE assigning: the original code assigned first and then swapped
        // two equal values, collapsing both pickers to the same language —
        // a defect present since the initial import, caught by the design §10
        // swap-on-conflict test (Phase 5) and fixed to match the design.
        if (_targetLanguage.value.code == language.code) {
            swapLanguages()
        } else {
            _sourceLanguage.value = language
            // Any change of source invalidates a previous detection, including
            // switching back to Auto-detect for a fresh message.
            _detectedSourceLanguage.value = null
            triggerTranslation()
        }
    }

    fun setTargetLanguage(language: Language) {
        if (_targetLanguage.value.code == language.code) return
        // Same swap-before-assign rule as setSourceLanguage.
        if (_sourceLanguage.value.code == language.code) {
            swapLanguages()
        } else {
            _targetLanguage.value = language
            triggerTranslation()
        }
    }

    fun swapLanguages() {
        // "Auto-detect" is a request, not a language — it cannot become the
        // target. Swapping resolves it to whatever was detected, and refuses
        // while nothing has been.
        if (_sourceLanguage.value.code == Language.AUTO_CODE) {
            val detected = _detectedSourceLanguage.value
            if (detected == null) {
                showMessage(strings.translateFirstToDetect)
                return
            }
            _sourceLanguage.value = detected
            _detectedSourceLanguage.value = null
        }

        val temp = _sourceLanguage.value
        _sourceLanguage.value = _targetLanguage.value
        _targetLanguage.value = temp

        val originalSourceText = _sourceText.value
        val state = _translationState.value
        if (state is TranslationState.Success) {
            _sourceText.value = state.translatedText
            triggerTranslation()
        } else if (originalSourceText.isNotBlank()) {
            triggerTranslation()
        }
    }

    /**
     * Hand the phone over. Swaps the two languages and clears the workspace, so
     * the other person can answer in theirs.
     *
     * This is the useful half of the web build's approve step, which commits the
     * draft and then swaps for the reply. The other half — holding the
     * translation back until it is approved — is deliberately not ported: the
     * translate tab is a lookup surface where the answer should appear at once,
     * and conversation mode already has a draft flow for the case where a turn
     * really is being sent to someone.
     *
     * Distinct from [swapLanguages], which carries the translation into the
     * input and re-translates it — that answers "what does this come back as",
     * and costs two Gemini calls. This one starts a blank turn and costs none.
     */
    fun handOverForReply() {
        // Same rule as swapLanguages: Auto-detect cannot become the target, so
        // it resolves to what was found, and refuses while nothing has been.
        if (_sourceLanguage.value.code == Language.AUTO_CODE) {
            val detected = _detectedSourceLanguage.value
            if (detected == null) {
                showMessage(strings.translateFirstToDetect)
                return
            }
            _sourceLanguage.value = detected
        }
        // The new source is a language the user chose, so any earlier detection
        // no longer describes it.
        _detectedSourceLanguage.value = null

        val previousSource = _sourceLanguage.value
        _sourceLanguage.value = _targetLanguage.value
        _targetLanguage.value = previousSource

        _sourceText.value = ""
        _translationState.value = TranslationState.Idle
        _validationState.value = ValidationState.Idle
        _rewriteState.value = RewriteState.Idle
    }

    fun setAiEnhanced(enabled: Boolean) {
        _isAiEnhanced.value = enabled
        if (_sourceText.value.isNotBlank()) {
            triggerTranslation()
        }
    }

    fun triggerTranslation() {
        val text = _sourceText.value
        if (text.isBlank()) {
            _translationState.value = TranslationState.Idle
            _validationState.value = ValidationState.Idle
            return
        }

        _translationState.value = TranslationState.Loading
        _validationState.value = ValidationState.Loading
        // A rewrite belongs to the message that triggered it; a new translation
        // must not inherit the previous one's suggestion.
        _rewriteState.value = RewriteState.Idle

        viewModelScope.launch {
            try {
                val src = _sourceLanguage.value
                val trg = _targetLanguage.value
                // The Translate tab has no session, so no conversation turns are
                // sent — only the tone/situation presets (see the port notes).
                val context = translationContext()

                // 1. Initial translation (Fast)
                val primary = translatePrimary(text, src, trg, context)

                // 2. Publish + parallel tasks: Round Trip & Validation
                publishTranslation(text, primary, trg, context)

            } catch (e: Exception) {
                _translationState.value = TranslationState.Error(e.message ?: strings.translationFailed)
            }
        }
    }

    /**
     * The tail of the translate pipeline, shared by the text and image entry
     * points: publish the card, persist, broadcast to the watch, then run the
     * round trip and the cultural validation in parallel.
     *
     * Both children carry the same staleness guard — a slow answer must not
     * land on a newer translation's card.
     */
    private suspend fun CoroutineScope.publishTranslation(
        originalText: String,
        primary: PrimaryTranslation,
        target: Language,
        context: TranslationContext,
    ) {
        val translated = primary.text
        // The language actually translated from: what was selected, or what
        // auto-detect found.
        val effectiveSource = primary.source

        _translationState.value = TranslationState.Success(originalText, translated)
        saveTranslationToDatabase(originalText, translated, effectiveSource)
        // Platform side-effect (Wear sync + notification) — moved out of the
        // UI's LaunchedEffect into the VM so the UI owns no platform I/O.
        broadcaster.broadcast(originalText, translated, effectiveSource, target)

        if (effectiveSource.code != Language.AUTO_CODE) {
            // Nothing to translate back *to* when detection came up empty; the
            // round trip is skipped rather than guessed.
            launch {
                try {
                    val roundTrip = api.translateGoogle(
                        text = translated,
                        sourceLang = target.code,
                        targetLang = effectiveSource.code
                    )
                    val currentState = _translationState.value
                    if (currentState is TranslationState.Success && currentState.translatedText == translated) {
                        _translationState.value = currentState.copy(roundTripText = roundTrip)
                    }
                } catch (e: Exception) {
                    log.e(TAG, "Round trip exception", e)
                }
            }
        }

        launch {
            val validation = runValidation(originalText, translated, primary.sourceNameForPrompt, target.name, context)
            val currentState = _translationState.value
            if (currentState is TranslationState.Success && currentState.translatedText == translated) {
                _validationState.value = validation
            }
        }
    }

    /**
     * A finished primary translation. [source] is the language actually
     * translated *from* — the same one that was asked for, or what auto-detect
     * settled on. [sourceNameForPrompt] is what the validation prompt should
     * call it, which differs only when detection returned a language this app
     * does not list.
     */
    private class PrimaryTranslation(
        val text: String,
        val source: Language,
        val sourceNameForPrompt: String,
    )

    /**
     * Primary translation call, honouring the AI-Enhanced (Gemini vs Google)
     * toggle — and overriding it when the [context] carries a tone or situation
     * Google's endpoint cannot express.
     *
     * An auto-detect source routes to the detecting variants, which report the
     * language back so the round trip, the history row and the validation
     * prompt all have a real language to work with.
     */
    private suspend fun translatePrimary(
        text: String,
        source: Language,
        target: Language,
        context: TranslationContext,
    ): PrimaryTranslation {
        val useGemini = _isAiEnhanced.value || context.needsAiModel
        if (source.code != Language.AUTO_CODE) {
            val translated = if (useGemini) {
                api.translateGemini(text, source.name, target.name, context)
            } else {
                api.translateGoogle(text, source.code, target.code)
            }
            return PrimaryTranslation(translated, source, source.name)
        }

        val detected = if (useGemini) {
            api.detectAndTranslateGemini(text, target.name, context)
        } else {
            api.detectAndTranslateGoogle(text, target.code)
        }
        val resolved = Language.findByCode(detected.detectedLangCode)
            ?.takeIf { it.code != Language.AUTO_CODE }
        _detectedSourceLanguage.value = resolved
        return PrimaryTranslation(
            text = detected.text,
            // Unresolved detection keeps the auto sentinel: it has no language
            // tag and no code, which is exactly what the round trip and the
            // history row need to know so they can decline.
            source = resolved ?: Language.autoDetect,
            sourceNameForPrompt = resolved?.name
                ?: detected.detectedLangCode.ifBlank { Language.autoDetect.name },
        )
    }

    /**
     * Cultural validation shared by the translate and conversation-draft pipelines.
     * Returns [ValidationState.Success] or, on failure, [ValidationState.Error]
     * with the same "Validation failed: …" message as before — except for the
     * exhausted-quota case, whose message already reads as a plain sentence and
     * would only be muddied by the prefix.
     */
    private suspend fun runValidation(
        originalText: String,
        translatedText: String,
        sourceLangName: String,
        targetLangName: String,
        context: TranslationContext,
    ): ValidationState = try {
        val result = api.validateTranslation(originalText, translatedText, sourceLangName, targetLangName, context)
        ValidationState.Success(
            isWarning = result.isWarning,
            explanation = result.explanation,
            suggestions = result.suggestions,
            sanityCheck = result.sanityCheck,
            idiomExplanation = result.idiomExplanation,
        )
    } catch (e: Exception) {
        log.e(TAG, "Validation exception", e)
        val message = e.message
        if (message == KtorTranslationApi.GEMINI_QUOTA_MESSAGE) {
            ValidationState.Error(message)
        } else {
            ValidationState.Error(strings.validationFailed.withArg(message.orEmpty()))
        }
    }

    // --- Image OCR translation ---

    /**
     * True while a photo is being read. The card shows a loading state either
     * way; this only changes what it says, because "Translating..." under a
     * photo that has not been read yet is a lie.
     */
    private val _isReadingImage = MutableStateFlow(false)
    val isReadingImage = _isReadingImage.asStateFlow()

    /**
     * Routes a picked image identically on every platform, mirroring
     * [onVoiceInputResult]: read the text, then hand the result to the ordinary
     * pipeline so it gets the same round trip, validation, history row and
     * watch broadcast a typed translation gets.
     *
     * The image itself is not persisted — the history schema is unchanged, and
     * a photo per row is a lot of storage for something nobody reads twice.
     */
    fun onImageInputResult(base64: String?, mimeType: String) {
        if (base64.isNullOrBlank()) return

        _isReadingImage.value = true
        _translationState.value = TranslationState.Loading
        _validationState.value = ValidationState.Loading
        _rewriteState.value = RewriteState.Idle

        viewModelScope.launch {
            val src = _sourceLanguage.value
            val trg = _targetLanguage.value
            try {
                val result = api.translateImage(
                    imageBase64 = base64,
                    mimeType = mimeType,
                    userLangName = src.name,
                    otherLangName = trg.name,
                    isSourceAutoDetect = src.code == Language.AUTO_CODE,
                )
                _isReadingImage.value = false

                // The transcription becomes the input, so it can be edited and
                // re-translated like anything else the user typed. Assigned
                // directly rather than through setSourceText, whose blank-text
                // branch would reset the card we are about to publish.
                val originalText = result.originalText.ifBlank { result.translation }
                _sourceText.value = originalText

                val isAutoSource = src.code == Language.AUTO_CODE
                val detected = Language.findByCode(result.detectedLangCode)
                    ?.takeIf { it.code != Language.AUTO_CODE }
                if (isAutoSource) {
                    _detectedSourceLanguage.value = detected
                }

                // Which way the prompt sent it: with an auto source everything
                // goes to the other party's language; otherwise text in the
                // user's own language goes out and anything else comes back.
                val target = if (isAutoSource || detected?.code == src.code) trg else src
                val primary = PrimaryTranslation(
                    text = result.translation,
                    // Unresolved detection keeps the auto sentinel, which is
                    // what tells publishTranslation to skip the round trip.
                    source = detected ?: Language.autoDetect,
                    sourceNameForPrompt = detected?.name
                        ?: result.detectedLangCode.ifBlank { Language.autoDetect.name },
                )

                publishTranslation(originalText, primary, target, translationContext())
            } catch (e: Exception) {
                log.e(TAG, "Image translation failed", e)
                _isReadingImage.value = false
                _translationState.value = TranslationState.Error(e.message ?: strings.imageReadFailed)
                _validationState.value = ValidationState.Idle
            }
        }
    }

    // --- Rewrite (on demand; the only user-triggered Gemini call) ---

    /**
     * Asks Gemini to rewrite the *source* message so it stops tripping the
     * cultural warning. With no [direction] this is the opening request and the
     * answer carries a couple of other angles to try; passing one of those back
     * re-runs it that way.
     *
     * The web build fires the opening rewrite automatically on every warning.
     * Here it is a button: on the free tier an automatic third request per
     * message is most of the daily allowance spent on suggestions nobody asked
     * for.
     */
    fun requestRewrite(direction: String? = null) {
        val translation = _translationState.value as? TranslationState.Success ?: return
        val validation = _validationState.value as? ValidationState.Success ?: return

        // What the model has to resolve: the warning if there is one, plus any
        // idiom it flagged. Falls back to the plain explanation so a rewrite
        // asked for on a clean translation still has something to work with.
        val warning = listOfNotNull(
            validation.explanation.takeIf { validation.isWarning && it.isNotBlank() },
            validation.idiomExplanation,
        ).joinToString("\n").ifBlank { validation.explanation }

        val previous = _rewriteState.value as? RewriteState.Success
        _rewriteState.value = if (previous != null && direction != null) {
            previous.copy(reloadingDirection = direction)
        } else {
            RewriteState.Loading
        }

        viewModelScope.launch {
            try {
                val result = api.rewriteMessage(
                    originalText = translation.originalText,
                    sourceLangName = _sourceLanguage.value.name,
                    targetLangName = _targetLanguage.value.name,
                    warning = warning,
                    direction = direction,
                    context = translationContext(),
                )
                // A directed rewrite answers with the new text alone; keep the
                // alternatives already on screen so the user can try another.
                _rewriteState.value = RewriteState.Success(
                    direction = result.rewriteDirection.ifBlank { direction.orEmpty() },
                    rewrittenSource = result.rewrittenSource,
                    alternativeDirections = result.alternativeDirections
                        .ifEmpty { previous?.alternativeDirections.orEmpty() },
                )
            } catch (e: Exception) {
                log.e(TAG, "Rewrite failed", e)
                _rewriteState.value = RewriteState.Error(e.message ?: strings.rewriteFailed)
            }
        }
    }

    /** Puts a rewrite in the input box and translates it. */
    fun applyRewrite(text: String) {
        _rewriteState.value = RewriteState.Idle
        setSourceText(text)
        triggerTranslation()
    }

    fun dismissRewrite() {
        _rewriteState.value = RewriteState.Idle
    }

    private suspend fun saveTranslationToDatabase(
        original: String,
        translated: String,
        source: Language = _sourceLanguage.value,
    ) {
        try {
            // timestamp = 0L is the "now" sentinel; the Room impl fills it with
            // System.currentTimeMillis(), matching the previous entity default.
            val translation = Translation(
                sourceText = original,
                translatedText = translated,
                sourceLangCode = source.code,
                sourceLangName = source.name,
                targetLangCode = _targetLanguage.value.code,
                targetLangName = _targetLanguage.value.name,
                timestamp = 0L,
            )
            repository.insert(translation)
        } catch (e: Exception) {
            log.e(TAG, "Failed saving translation to database: ", e)
        }
    }

    fun toggleStarred(translation: Translation) {
        viewModelScope.launch {
            repository.setStarred(translation.id, !translation.isStarred)
        }
    }

    fun deleteHistoricalItem(id: Long) {
        viewModelScope.launch {
            repository.deleteById(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    // --- Conversation Mode Methods ---

    fun setConversationMode(enabled: Boolean) {
        // Conversation mode labels both speakers, so neither side can be an
        // open question. Resolve Auto-detect to what was found, or ask first.
        if (enabled && _sourceLanguage.value.code == Language.AUTO_CODE) {
            val detected = _detectedSourceLanguage.value
            if (detected == null) {
                showMessage(strings.pickLanguageBeforeConversation)
                return
            }
            _sourceLanguage.value = detected
            _detectedSourceLanguage.value = null
        }
        _isConversationMode.value = enabled
        if (!enabled) {
            _conversationHistory.value = emptyList()
            _partnerAText.value = ""
            _partnerBText.value = ""
        }
    }

    fun prepareConversationPart(text: String, isLanguageA: Boolean) {
        if (text.isBlank()) return
        
        _conversationDraft.value = ConversationDraft(
            isLanguageA = isLanguageA,
            sourceText = text,
            isTranslating = true,
            validationState = ValidationState.Loading
        )

        viewModelScope.launch {
            try {
                // Determine languages based on who's speaking
                val sourceLangName = if (isLanguageA) _sourceLanguage.value.name else _targetLanguage.value.name
                val targetLangName = if (isLanguageA) _targetLanguage.value.name else _sourceLanguage.value.name
                val sourceLangCode = if (isLanguageA) _sourceLanguage.value.code else _targetLanguage.value.code
                val targetLangCode = if (isLanguageA) _targetLanguage.value.code else _sourceLanguage.value.code
                val context = translationContext(conversationTurns())

                val translation = if (_isAiEnhanced.value || context.needsAiModel) {
                    api.translateGemini(text, sourceLangName, targetLangName, context)
                } else {
                    api.translateGoogle(text, sourceLangCode, targetLangCode)
                }

                _conversationDraft.value = _conversationDraft.value?.copy(
                    translatedText = translation,
                    isTranslating = false
                )

                // Parallel: round trip and cultural validation
                launch {
                    try {
                        val roundTrip = api.translateGoogle(translation, targetLangCode, sourceLangCode)
                        _conversationDraft.value = _conversationDraft.value?.copy(roundTripText = roundTrip)
                    } catch (e: Exception) {
                        log.e(TAG, "Draft round trip exception", e)
                    }
                }

                launch {
                    val validation = runValidation(text, translation, sourceLangName, targetLangName, context)
                    _conversationDraft.value = _conversationDraft.value?.copy(validationState = validation)
                }

            } catch (e: Exception) {
                log.e(TAG, "Conversation mode translation failed: ", e)
                _conversationDraft.value = _conversationDraft.value?.copy(
                    isTranslating = false,
                    validationState = ValidationState.Error(e.message ?: strings.conversationTranslationFailed)
                )
            }
        }
    }

    fun confirmConversationDraft(onTranslationDone: (String, Boolean) -> Unit) {
        val draft = _conversationDraft.value ?: return
        if (draft.translatedText.isNotBlank()) {
            val item = ConversationItem(
                text = draft.sourceText,
                translated = draft.translatedText,
                speakA = draft.isLanguageA
            )
            _conversationHistory.value = _conversationHistory.value + item
            
            if (draft.isLanguageA) {
                _partnerAText.value = draft.sourceText
            } else {
                _partnerBText.value = draft.sourceText
            }
            
            onTranslationDone(draft.translatedText, draft.isLanguageA)
        }
        _conversationDraft.value = null
    }

    fun cancelConversationDraft() {
        _conversationDraft.value = null
    }
}
