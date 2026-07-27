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
import com.example.wear.TranslationBroadcaster
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
) : ViewModel() {
    companion object {
        private const val TAG = "TranslateViewModel"

        /**
         * The "Speak now" voice prompt, shared so platforms cannot drift:
         * Android shows it inside the RecognizerIntent dialog (EXTRA_PROMPT),
         * web surfaces it as a snackbar (no system dialog exists there).
         */
        fun voiceInputPrompt(language: Language): String = "Speak now (${language.name})..."

        /** Shown when launching the platform recognizer fails outright. */
        const val VOICE_INPUT_UNSUPPORTED_MESSAGE = "Voice input is not supported on this device."

        /** How many prior conversation turns are sent as prompt context. */
        const val CONVERSATION_CONTEXT_TURNS = 10
    }

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

    // Configured selected languages
    private val _sourceLanguage = MutableStateFlow(Language.getByCode("en"))
    val sourceLanguage = _sourceLanguage.asStateFlow()

    private val _targetLanguage = MutableStateFlow(Language.getByCode("es"))
    val targetLanguage = _targetLanguage.asStateFlow()

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
            showMessage("AI Enhanced turned on — tone and situation need it.")
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

        viewModelScope.launch {
            try {
                val src = _sourceLanguage.value
                val trg = _targetLanguage.value
                // The Translate tab has no session, so no conversation turns are
                // sent — only the tone/situation presets (see the port notes).
                val context = translationContext()

                // 1. Initial translation (Fast)
                val translated = translatePrimary(text, src, trg, context)

                _translationState.value = TranslationState.Success(text, translated)
                saveTranslationToDatabase(text, translated)
                // Platform side-effect (Wear sync + notification) — moved out of the
                // UI's LaunchedEffect into the VM so the UI owns no platform I/O.
                broadcaster.broadcast(text, translated, src, trg)

                // 2. Parallel tasks: Round Trip & Validation
                launch {
                    try {
                        val roundTrip = api.translateGoogle(
                            text = translated,
                            sourceLang = trg.code,
                            targetLang = src.code
                        )
                        val currentState = _translationState.value
                        if (currentState is TranslationState.Success && currentState.translatedText == translated) {
                            _translationState.value = currentState.copy(roundTripText = roundTrip)
                        }
                    } catch (e: Exception) {
                        log.e(TAG, "Round trip exception", e)
                    }
                }

                launch {
                    val validation = runValidation(text, translated, src.name, trg.name, context)
                    // Same staleness guard as the round trip above: a slow
                    // validation must not land on a newer translation's card.
                    val currentState = _translationState.value
                    if (currentState is TranslationState.Success && currentState.translatedText == translated) {
                        _validationState.value = validation
                    }
                }

            } catch (e: Exception) {
                _translationState.value = TranslationState.Error(e.message ?: "Failed to perform translation")
            }
        }
    }

    /**
     * Primary translation call, honouring the AI-Enhanced (Gemini vs Google)
     * toggle — and overriding it when the [context] carries a tone or situation
     * Google's endpoint cannot express.
     */
    private suspend fun translatePrimary(
        text: String,
        source: Language,
        target: Language,
        context: TranslationContext,
    ): String =
        if (_isAiEnhanced.value || context.needsAiModel) {
            api.translateGemini(text, source.name, target.name, context)
        } else {
            api.translateGoogle(text, source.code, target.code)
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
            ValidationState.Error("Validation failed: $message")
        }
    }

    private suspend fun saveTranslationToDatabase(original: String, translated: String) {
        try {
            // timestamp = 0L is the "now" sentinel; the Room impl fills it with
            // System.currentTimeMillis(), matching the previous entity default.
            val translation = Translation(
                sourceText = original,
                translatedText = translated,
                sourceLangCode = _sourceLanguage.value.code,
                sourceLangName = _sourceLanguage.value.name,
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
                    validationState = ValidationState.Error(e.message ?: "Failed at translation stage")
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
