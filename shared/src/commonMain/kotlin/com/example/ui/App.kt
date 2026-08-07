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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.Language
import com.example.platform.AppContainer
import com.example.platform.ImageSource
import com.example.ui.components.AppTopBar
import com.example.ui.components.BottomNavBar
import com.example.ui.components.FullScreenTextOverlay
import com.example.ui.components.HelpDrawer
import com.example.ui.components.LanguageBar
import com.example.ui.components.LanguagePickerDialog
import com.example.ui.components.ValidationDetailsSheet
import com.example.ui.i18n.LocalStrings
import com.example.ui.i18n.stringsFor
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
fun rememberTranslateViewModel(container: AppContainer): TranslateViewModel {
    // Read outside `viewModel {}`: the factory body is not composable, and the
    // VM needs the catalogue for the messages it authors itself.
    val strings = stringsFor(Locale.current.language)
    return viewModel {
        TranslateViewModel(
            api = container.api,
            repository = container.historyRepository,
            broadcaster = container.broadcaster,
            log = container.log,
            strings = strings,
            link = container.conversationLink,
        )
    }
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
@Composable
fun App(
    container: AppContainer,
    launchVoiceInput: (isConversationA: Boolean?, language: Language) -> Unit,
    launchImageInput: (source: ImageSource) -> Unit = {},
    /**
     * Issue #33: opens the platform QR scanner for pairing. Same shape and the
     * same reason as [launchVoiceInput] — the GMS Code Scanner needs an Activity,
     * so the caller owns it. Defaulted to a no-op for the web build, which has
     * `remoteConversation = false` and never shows the affordance anyway.
     */
    launchQrScan: () -> Unit = {},
) {
    // The device language chooses the string catalogue; an untranslated language
    // — or an untranslated string inside a translated one — falls back to
    // English inside `stringsFor`. `Locale.current` is Compose's own
    // multiplatform read, so this needs no platform seam.
    val languageTag = Locale.current.language
    val strings = remember(languageTag) { stringsFor(languageTag) }
    CompositionLocalProvider(LocalStrings provides strings) {
        AppContent(container, launchVoiceInput, launchImageInput, launchQrScan)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppContent(
    container: AppContainer,
    launchVoiceInput: (isConversationA: Boolean?, language: Language) -> Unit,
    launchImageInput: (source: ImageSource) -> Unit,
    launchQrScan: () -> Unit,
) {
    MyApplicationTheme {
        val keyboardController = LocalSoftwareKeyboardController.current
        val strings = LocalStrings.current

        val viewModel = rememberTranslateViewModel(container)

        // Collect model flows
        val sourceText by viewModel.sourceText.collectAsState()
        val translationState by viewModel.translationState.collectAsState()
        val validationState by viewModel.validationState.collectAsState()
        val rewriteState by viewModel.rewriteState.collectAsState()
        val sourceLang by viewModel.sourceLanguage.collectAsState()
        val targetLang by viewModel.targetLanguage.collectAsState()
        val detectedSourceLang by viewModel.detectedSourceLanguage.collectAsState()
        // "Auto-detect" has no BCP-47 tag, so speech in and out need the
        // language it resolved to; before anything is detected there is none,
        // and the tag stays blank (the mic and speaker guard on that).
        val speechSourceLang = if (sourceLang.code == Language.AUTO_CODE) {
            detectedSourceLang ?: sourceLang
        } else {
            sourceLang
        }
        val isAiEnhanced by viewModel.isAiEnhanced.collectAsState()
        val tone by viewModel.tone.collectAsState()
        val situation by viewModel.situation.collectAsState()
        val history by viewModel.history.collectAsState()
        val starred by viewModel.starred.collectAsState()
        val isConversationMode by viewModel.isConversationMode.collectAsState()
        val isReadingImage by viewModel.isReadingImage.collectAsState()

        // Navigation and Dialog state
        var activeBottomTab by remember { mutableStateOf("translate") } // "translate", "history", "saved"
        var choosingSourceLanguage by remember { mutableStateOf(false) }
        var choosingTargetLanguage by remember { mutableStateOf(false) }
        var showValidationDetails by remember { mutableStateOf<ValidationState.Success?>(null) }
        // The flashcard is reachable from three places, so its state is here.
        var fullScreenText by remember { mutableStateOf<String?>(null) }
        var showHelp by remember { mutableStateOf(false) }

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
                            onShowFullScreen = { fullScreenText = it },
                            showRemoteConversation = container.capabilities.remoteConversation,
                            launchQrScan = launchQrScan,
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
                                        onShowHelp = { showHelp = true },
                                    )

                                    LanguageBar(
                                        sourceLang = sourceLang,
                                        targetLang = targetLang,
                                        detectedSourceLanguage = detectedSourceLang,
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
                                        sourceLang = speechSourceLang,
                                        targetLang = targetLang,
                                        translationState = translationState,
                                        validationState = validationState,
                                        history = history,
                                        starred = starred,
                                        tone = tone,
                                        situation = situation,
                                        isReadingImage = isReadingImage,
                                        speechSynthesizer = container.speechSynthesizer,
                                        platformActions = container.platformActions,
                                        showVoiceInput = container.capabilities.voiceInput,
                                        extendedGlyphs = container.capabilities.extendedGlyphs,
                                        launchVoiceInput = launchVoiceInput,
                                        onShowValidationDetails = { showValidationDetails = it },
                                        onShowFullScreen = { fullScreenText = it },
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
                                            viewModel.showMessage(strings.deletedFromHistory)
                                        },
                                        onSpeak = { item ->
                                            container.speechSynthesizer.speak(
                                                item.translatedText,
                                                Language.getByCode(item.targetLangCode).languageTag,
                                            )
                                        },
                                        onToggleStar = { item -> viewModel.toggleStarred(item) },
                                        onShowFullScreen = { fullScreenText = it },
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
                                onMic = {
                                    if (speechSourceLang.languageTag.isBlank()) {
                                        viewModel.showMessage(strings.pickLanguageToSpeak)
                                    } else {
                                        launchVoiceInput(null, speechSourceLang)
                                    }
                                },
                                showImageInput = container.capabilities.imageInput,
                                onPickImage = launchImageInput,
                                sourceTextBlank = sourceText.isBlank(),
                                onQuickEdit = {
                                    if (sourceText.isNotBlank()) {
                                        viewModel.setSourceText("")
                                    } else {
                                        // Load a pleasant prompt
                                        viewModel.setSourceText(strings.sampleText)
                                        viewModel.triggerTranslation()
                                    }
                                },
                            )
                        }
                    }

                    // --- Dialog Panel: Choose Source Language ---
                    if (choosingSourceLanguage) {
                        LanguagePickerDialog(
                            title = strings.pickerTitleSource,
                            currentlySelected = sourceLang,
                            includeAuto = true,
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
                            title = strings.pickerTitleTarget,
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
                        rewriteState = rewriteState,
                        onDismiss = { showValidationDetails = null },
                        onSelectSuggestion = { text ->
                            showValidationDetails = null
                            viewModel.setSourceText(text)
                            keyboardController?.hide()
                            viewModel.triggerTranslation()
                        },
                        onRequestRewrite = { direction -> viewModel.requestRewrite(direction) },
                        onApplyRewrite = { text ->
                            showValidationDetails = null
                            keyboardController?.hide()
                            viewModel.applyRewrite(text)
                        },
                    )

                    // --- Overlay Panel: the translation, alone and large ---
                    FullScreenTextOverlay(
                        text = fullScreenText,
                        onDismiss = { fullScreenText = null },
                    )

                    // --- Overlay Panel: what the app does ---
                    HelpDrawer(
                        visible = showHelp,
                        showVoiceInput = container.capabilities.voiceInput,
                        showImageInput = container.capabilities.imageInput,
                        showDualHeadset = container.capabilities.dualHeadset,
                        onDismiss = { showHelp = false },
                    )
                }
            }
        }
    }
}
