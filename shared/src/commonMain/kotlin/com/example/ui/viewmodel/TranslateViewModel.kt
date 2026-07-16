package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.TranslationHistoryRepository
import com.example.model.Language
import com.example.model.Translation
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
        _sourceLanguage.value = language
        // If it matches target language, swap them
        if (_targetLanguage.value.code == language.code) {
            swapLanguages()
        } else {
            triggerTranslation()
        }
    }

    fun setTargetLanguage(language: Language) {
        if (_targetLanguage.value.code == language.code) return
        _targetLanguage.value = language
        if (_sourceLanguage.value.code == language.code) {
            swapLanguages()
        } else {
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

                // 1. Initial translation (Fast)
                val translated = translatePrimary(text, src, trg)

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
                    _validationState.value = runValidation(text, translated, src.name, trg.name)
                }

            } catch (e: Exception) {
                _translationState.value = TranslationState.Error(e.message ?: "Failed to perform translation")
            }
        }
    }

    /** Primary translation call, honouring the AI-Enhanced (Gemini vs Google) toggle. */
    private suspend fun translatePrimary(text: String, source: Language, target: Language): String =
        if (_isAiEnhanced.value) {
            api.translateGemini(text, source.name, target.name)
        } else {
            api.translateGoogle(text, source.code, target.code)
        }

    /**
     * Cultural validation shared by the translate and conversation-draft pipelines.
     * Returns [ValidationState.Success] or, on failure, [ValidationState.Error]
     * with the same "Validation failed: …" message as before.
     */
    private suspend fun runValidation(
        originalText: String,
        translatedText: String,
        sourceLangName: String,
        targetLangName: String,
    ): ValidationState = try {
        val result = api.validateTranslation(originalText, translatedText, sourceLangName, targetLangName)
        ValidationState.Success(result.isWarning, result.explanation, result.suggestions)
    } catch (e: Exception) {
        log.e(TAG, "Validation exception", e)
        ValidationState.Error("Validation failed: ${e.message}")
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

                val translation = if (_isAiEnhanced.value) {
                    api.translateGemini(text, sourceLangName, targetLangName)
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
                    val validation = runValidation(text, translation, sourceLangName, targetLangName)
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
