package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.TranslationDao
import com.example.data.TranslationDatabase
import com.example.data.TranslationEntity
import com.example.model.Language
import com.example.network.TranslationParsers
import com.example.network.TranslationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TranslationState {
    object Idle : TranslationState
    object Loading : TranslationState
    data class Success(val originalText: String, val translatedText: String, val roundTripText: String? = null) : TranslationState
    data class Error(val message: String) : TranslationState
}

data class Suggestion(val tone: String, val text: String)

sealed interface ValidationState {
    object Idle : ValidationState
    object Loading : ValidationState
    data class Success(
        val isWarning: Boolean,
        val explanation: String,
        val suggestions: List<Suggestion>
    ) : ValidationState
    data class Error(val message: String) : ValidationState
}

data class ConversationDraft(
    val isLanguageA: Boolean,
    val sourceText: String,
    val translatedText: String = "",
    val roundTripText: String? = null,
    val isTranslating: Boolean = false,
    val validationState: ValidationState = ValidationState.Idle
)

class TranslateViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "TranslateViewModel"
    }
    private val translationDao: TranslationDao = TranslationDatabase.getDatabase(application).translationDao()

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
    val history: StateFlow<List<TranslationEntity>> = translationDao.getAllTranslations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val starred: StateFlow<List<TranslationEntity>> = translationDao.getStarredTranslations()
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
        val timestamp: Long = System.currentTimeMillis()
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
                // 1. Initial translation (Fast)
                val translated = if (_isAiEnhanced.value) {
                    TranslationService.translateGemini(
                        text = text,
                        sourceLangName = _sourceLanguage.value.name,
                        targetLangName = _targetLanguage.value.name
                    )
                } else {
                    TranslationService.translateGoogle(
                        text = text,
                        sourceLang = _sourceLanguage.value.code,
                        targetLang = _targetLanguage.value.code
                    )
                }

                _translationState.value = TranslationState.Success(text, translated)
                saveTranslationToDatabase(text, translated)

                // 2. Parallel tasks: Round Trip & Validation
                launch {
                    try {
                        val roundTrip = TranslationService.translateGoogle(
                            text = translated,
                            sourceLang = _targetLanguage.value.code,
                            targetLang = _sourceLanguage.value.code
                        )
                        val currentState = _translationState.value
                        if (currentState is TranslationState.Success && currentState.translatedText == translated) {
                            _translationState.value = currentState.copy(roundTripText = roundTrip)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Round trip exception", e)
                    }
                }

                launch {
                    try {
                        val json = TranslationService.validateTranslationGemini(
                            originalText = text,
                            translatedText = translated,
                            sourceLangName = _sourceLanguage.value.name,
                            targetLangName = _targetLanguage.value.name
                        )
                        
                        val parsed = TranslationParsers.parseValidation(json)
                        val suggestionsList = parsed.suggestions.map { Suggestion(it.tone, it.text) }

                        _validationState.value = ValidationState.Success(parsed.isWarning, parsed.explanation, suggestionsList)
                    } catch (e: Exception) {
                        Log.e(TAG, "Validation exception", e)
                        _validationState.value = ValidationState.Error("Validation failed: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                _translationState.value = TranslationState.Error(e.message ?: "Failed to perform translation")
            }
        }
    }

    private suspend fun saveTranslationToDatabase(original: String, translated: String) {
        try {
            val entity = TranslationEntity(
                sourceText = original,
                translatedText = translated,
                sourceLangCode = _sourceLanguage.value.code,
                sourceLangName = _sourceLanguage.value.name,
                targetLangCode = _targetLanguage.value.code,
                targetLangName = _targetLanguage.value.name
            )
            translationDao.insertTranslation(entity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving translation to database: ", e)
        }
    }

    fun toggleStarred(translation: TranslationEntity) {
        viewModelScope.launch {
            translationDao.updateStarredState(translation.id, !translation.isStarred)
        }
    }

    fun deleteHistoricalItem(id: Long) {
        viewModelScope.launch {
            translationDao.deleteById(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            translationDao.clearHistory()
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
                    TranslationService.translateGemini(text, sourceLangName, targetLangName)
                } else {
                    TranslationService.translateGoogle(text, sourceLangCode, targetLangCode)
                }
                
                _conversationDraft.value = _conversationDraft.value?.copy(
                    translatedText = translation,
                    isTranslating = false
                )

                // Parallel: round trip and cultural validation
                launch {
                    try {
                        val roundTrip = TranslationService.translateGoogle(translation, targetLangCode, sourceLangCode)
                        _conversationDraft.value = _conversationDraft.value?.copy(roundTripText = roundTrip)
                    } catch (e: Exception) {
                        Log.e(TAG, "Draft round trip exception", e)
                    }
                }
                
                launch {
                    try {
                        val json = TranslationService.validateTranslationGemini(
                            originalText = text,
                            translatedText = translation,
                            sourceLangName = sourceLangName,
                            targetLangName = targetLangName
                        )
                        val parsed = TranslationParsers.parseValidation(json)
                        val suggestionsList = parsed.suggestions.map { Suggestion(it.tone, it.text) }
                        _conversationDraft.value = _conversationDraft.value?.copy(
                            validationState = ValidationState.Success(parsed.isWarning, parsed.explanation, suggestionsList)
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Draft validation exception", e)
                        _conversationDraft.value = _conversationDraft.value?.copy(validationState = ValidationState.Error("Validation failed: ${e.message}"))
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Conversation mode translation failed: ", e)
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
