package com.example.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.Language
import com.example.platform.AppContainer
import com.example.ui.components.AppTopBar
import com.example.ui.components.BottomNavBar
import com.example.ui.components.LanguageBar
import com.example.ui.components.LanguagePickerDialog
import com.example.ui.components.ValidationDetailsSheet
import com.example.ui.screens.ConversationScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.TranslateScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.TranslateViewModel
import com.example.ui.viewmodel.ValidationState

/**
 * The single [TranslateViewModel] factory. Both [App] and the Android entry point
 * (whose voice launcher needs the VM before `App` runs) resolve the VM through
 * this, so there is one factory definition and — because `viewModel {}` keys on
 * the VM type under the shared [LocalViewModelStoreOwner] — one instance.
 */
@Composable
fun rememberTranslateViewModel(container: AppContainer): TranslateViewModel = viewModel {
    TranslateViewModel(
        api = container.api,
        repository = container.historyRepository,
        broadcaster = container.broadcaster,
        log = container.log,
    )
}

/**
 * Root shared composable (design §1/§9). Owns the [MyApplicationTheme], builds the
 * [TranslateViewModel] from the injected [container] via the CMP `viewModel {}`
 * factory (works on both targets), and assembles the whole app shell — top bar,
 * language bar, the three screens, bottom nav, language/validation dialogs, and
 * the snackbar host fed by `viewModel.messages`.
 *
 * Platform entry points call `App(container, launchVoiceInput)`:
 * - Android: `MainActivity.setContent { App(container, ::launchVoiceInput) }`,
 *   where `launchVoiceInput` bridges to the RecognizerIntent activity launcher.
 * - Web: `ComposeViewport { App(container, launchVoiceInput) }`, where the lambda
 *   routes through `container.voiceInput` (WebVoiceInput on Chromium; elsewhere
 *   the mic UI is hidden by `capabilities.voiceInput = false`).
 *
 * [launchVoiceInput] stays a caller-supplied callback because the Android
 * implementation needs an Activity-scoped result launcher that can't live in
 * commonMain (design §8/§9); the `pendingVoiceRequestIsA` routing lives in the
 * caller.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun App(
    container: AppContainer,
    launchVoiceInput: (isConversationA: Boolean?, language: Language) -> Unit,
) {
    MyApplicationTheme {
        val keyboardController = LocalSoftwareKeyboardController.current

        val viewModel = rememberTranslateViewModel(container)

        // Collect model flows
        val sourceText by viewModel.sourceText.collectAsState()
        val translationState by viewModel.translationState.collectAsState()
        val validationState by viewModel.validationState.collectAsState()
        val sourceLang by viewModel.sourceLanguage.collectAsState()
        val targetLang by viewModel.targetLanguage.collectAsState()
        val isAiEnhanced by viewModel.isAiEnhanced.collectAsState()
        val tone by viewModel.tone.collectAsState()
        val situation by viewModel.situation.collectAsState()
        val history by viewModel.history.collectAsState()
        val starred by viewModel.starred.collectAsState()
        val isConversationMode by viewModel.isConversationMode.collectAsState()

        // Navigation and Dialog state
        var activeBottomTab by remember { mutableStateOf("translate") } // "translate", "history", "saved"
        var choosingSourceLanguage by remember { mutableStateOf(false) }
        var choosingTargetLanguage by remember { mutableStateOf(false) }
        var showValidationDetails by remember { mutableStateOf<ValidationState.Success?>(null) }

        // Voice-input status/errors are surfaced as snackbars, driven by the VM's
        // one-shot message stream (replaces the old Toast calls).
        val snackbarHostState = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            viewModel.messages.collect { message ->
                snackbarHostState.showSnackbar(message)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbarHostState) }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (isConversationMode) {
                        // --- Conversation Split-Screen Panel ---
                        ConversationScreen(
                            viewModel = viewModel,
                            sourceLang = sourceLang,
                            targetLang = targetLang,
                            speechSynthesizer = container.speechSynthesizer,
                            audioOutputCatalog = container.audioOutputCatalog,
                            showVoiceInput = container.capabilities.voiceInput,
                            showDualHeadset = container.capabilities.dualHeadset,
                            extendedGlyphs = container.capabilities.extendedGlyphs,
                            launchVoiceInput = launchVoiceInput,
                        )
                    } else {
                        // --- High Density Google Translate Screen ---
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // 1. Core Header Panel (Rounded Bottom, Elevation, Contains Lang Selectors)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp)
                                ) {
                                    AppTopBar(
                                        viewModel = viewModel,
                                        isAiEnhanced = isAiEnhanced,
                                        audioOutputCatalog = container.audioOutputCatalog,
                                        showDualHeadset = container.capabilities.dualHeadset,
                                    )

                                    LanguageBar(
                                        sourceLang = sourceLang,
                                        targetLang = targetLang,
                                        extendedGlyphs = container.capabilities.extendedGlyphs,
                                        onSwap = { viewModel.swapLanguages() },
                                        onChooseSource = { choosingSourceLanguage = true },
                                        onChooseTarget = { choosingTargetLanguage = true },
                                    )
                                }
                            }

                            // 2. Middle Scrollable Main Content Workspace
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                if (activeBottomTab == "translate") {
                                    TranslateScreen(
                                        viewModel = viewModel,
                                        sourceText = sourceText,
                                        sourceLang = sourceLang,
                                        targetLang = targetLang,
                                        translationState = translationState,
                                        validationState = validationState,
                                        history = history,
                                        starred = starred,
                                        tone = tone,
                                        situation = situation,
                                        speechSynthesizer = container.speechSynthesizer,
                                        platformActions = container.platformActions,
                                        showVoiceInput = container.capabilities.voiceInput,
                                        extendedGlyphs = container.capabilities.extendedGlyphs,
                                        launchVoiceInput = launchVoiceInput,
                                        onShowValidationDetails = { showValidationDetails = it },
                                    )
                                } else {
                                    // History or Saved view (Lists)
                                    HistoryScreen(
                                        tab = activeBottomTab,
                                        items = if (activeBottomTab == "history") history else starred,
                                        showClearAll = activeBottomTab == "history" && history.isNotEmpty(),
                                        extendedGlyphs = container.capabilities.extendedGlyphs,
                                        onClearAll = { viewModel.clearHistory() },
                                        onOpenItem = { item ->
                                            viewModel.setSourceLanguage(Language.getByCode(item.sourceLangCode))
                                            viewModel.setTargetLanguage(Language.getByCode(item.targetLangCode))
                                            viewModel.setSourceText(item.sourceText)
                                            viewModel.triggerTranslation()
                                            activeBottomTab = "translate"
                                        },
                                        onDeleteItem = { item ->
                                            viewModel.deleteHistoricalItem(item.id)
                                            viewModel.showMessage("Deleted from history")
                                        },
                                        onSpeak = { item ->
                                            container.speechSynthesizer.speak(
                                                item.translatedText,
                                                Language.getByCode(item.targetLangCode).languageTag,
                                            )
                                        },
                                        onToggleStar = { item -> viewModel.toggleStarred(item) },
                                    )
                                }
                            }

                            // 3. Compact Bottom Interface Dock (Microphone + Navigation)
                            BottomNavBar(
                                activeBottomTab = activeBottomTab,
                                onSelectTab = { activeBottomTab = it },
                                showMicRow = activeBottomTab == "translate",
                                showVoiceInput = container.capabilities.voiceInput,
                                onConversationMode = { viewModel.setConversationMode(true) },
                                onMic = { launchVoiceInput(null, sourceLang) },
                                sourceTextBlank = sourceText.isBlank(),
                                onQuickEdit = {
                                    if (sourceText.isNotBlank()) {
                                        viewModel.setSourceText("")
                                    } else {
                                        // Load a pleasant prompt
                                        viewModel.setSourceText("Hello, how are you?")
                                        viewModel.triggerTranslation()
                                    }
                                },
                            )
                        }
                    }

                    // --- Dialog Panel: Choose Source Language ---
                    if (choosingSourceLanguage) {
                        LanguagePickerDialog(
                            title = "Translate from",
                            currentlySelected = sourceLang,
                            extendedGlyphs = container.capabilities.extendedGlyphs,
                            onDismiss = { choosingSourceLanguage = false },
                            onSelect = {
                                viewModel.setSourceLanguage(it)
                                choosingSourceLanguage = false
                            }
                        )
                    }

                    // --- Dialog Panel: Choose Target Language ---
                    if (choosingTargetLanguage) {
                        LanguagePickerDialog(
                            title = "Translate to",
                            currentlySelected = targetLang,
                            extendedGlyphs = container.capabilities.extendedGlyphs,
                            onDismiss = { choosingTargetLanguage = false },
                            onSelect = {
                                viewModel.setTargetLanguage(it)
                                choosingTargetLanguage = false
                            }
                        )
                    }

                    // --- Overlay Panel: Validation Details ---
                    ValidationDetailsSheet(
                        current = showValidationDetails,
                        onDismiss = { showValidationDetails = null },
                        onSelectSuggestion = { text ->
                            showValidationDetails = null
                            viewModel.setSourceText(text)
                            keyboardController?.hide()
                            viewModel.triggerTranslation()
                        },
                    )
                }
            }
        }
    }
}
