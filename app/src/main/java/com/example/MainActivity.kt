package com.example

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.Toast
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.BuildConfig
import com.example.data.RoomTranslationHistoryRepository
import com.example.data.TranslationDatabase
import com.example.model.Language
import com.example.network.KtorTranslationApi
import com.example.speech.TTSHelper
import com.example.ui.components.AppTopBar
import com.example.ui.components.BottomNavBar
import com.example.ui.components.LanguageBar
import com.example.ui.components.LanguagePickerDialog
import com.example.ui.components.ValidationDetailsSheet
import com.example.ui.screens.ConversationScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.TranslateScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.ValidationState
import com.example.ui.viewmodel.TranslateViewModel
import com.example.wear.WearTranslationBroadcaster

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainAppScreen()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainAppScreen() {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Hand-built dependencies for the view model. Replaced by AppContainer in the
    // task-6 platform-interface pass; a plain ViewModel + factory for now.
    val appContext = context.applicationContext
    val viewModel: TranslateViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                TranslateViewModel(
                    api = KtorTranslationApi(apiKey = BuildConfig.GEMINI_API_KEY),
                    repository = RoomTranslationHistoryRepository(
                        TranslationDatabase.getDatabase(appContext).translationDao()
                    ),
                    broadcaster = WearTranslationBroadcaster(appContext),
                )
            }
        }
    )

    // Collect model flows
    val sourceText by viewModel.sourceText.collectAsState()
    val translationState by viewModel.translationState.collectAsState()
    val validationState by viewModel.validationState.collectAsState()
    val sourceLang by viewModel.sourceLanguage.collectAsState()
    val targetLang by viewModel.targetLanguage.collectAsState()
    val isAiEnhanced by viewModel.isAiEnhanced.collectAsState()
    val history by viewModel.history.collectAsState()
    val starred by viewModel.starred.collectAsState()
    val isConversationMode by viewModel.isConversationMode.collectAsState()
    val partnerAText by viewModel.partnerAText.collectAsState()
    val partnerBText by viewModel.partnerBText.collectAsState()

    // Navigation and Dialog state
    var activeBottomTab by remember { mutableStateOf("translate") } // "translate", "history", "saved"
    var choosingSourceLanguage by remember { mutableStateOf(false) }
    var choosingTargetLanguage by remember { mutableStateOf(false) }
    var showValidationDetails by remember { mutableStateOf<ValidationState.Success?>(null) }

    // TTS management
    val ttsHelper = remember {
        TTSHelper(context) { success ->
            Log.d("MainActivity", "TTS Init success: $success")
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            ttsHelper.release()
        }
    }

    // Capture Speak inputs
    var pendingVoiceRequestIsA by remember { mutableStateOf<Boolean?>(null) } // true: A, false: B, null: Main Translation mic

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
            if (spokenText.isNotBlank()) {
                val reqIsA = pendingVoiceRequestIsA
                if (reqIsA != null) {
                    // Conversation mode
                    viewModel.prepareConversationPart(spokenText, isLanguageA = reqIsA)
                } else {
                    // Main layout speech input
                    viewModel.setSourceText(spokenText)
                    viewModel.triggerTranslation()
                }
            }
        }
        pendingVoiceRequestIsA = null
    }

    // Helper to start voice input
    fun launchVoiceInput(isConversationA: Boolean?, language: Language) {
        pendingVoiceRequestIsA = isConversationA
        
        val dualState = viewModel.dualHeadsetState.value
        if (dualState.isDualHeadsetModeEnabled && isConversationA != null) {
            val userStr = if (isConversationA) "User 1" else "User 2"
            viewModel.showMessage("Listening on $userStr's headset...")
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.languageTag)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, language.languageTag)
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(language.languageTag))
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now (${language.name})...")
            }
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            viewModel.showMessage("Voice input is not supported on this device.")
        }
    }

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
                    ttsHelper = ttsHelper,
                    context = context,
                    launchVoiceInput = { isConversationA, language -> launchVoiceInput(isConversationA, language) },
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
                            )

                            LanguageBar(
                                sourceLang = sourceLang,
                                targetLang = targetLang,
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
                                ttsHelper = ttsHelper,
                                context = context,
                                launchVoiceInput = { isConversationA, language -> launchVoiceInput(isConversationA, language) },
                                onShowValidationDetails = { showValidationDetails = it },
                            )
                        } else {
                            // History or Saved view (Lists)
                            HistoryScreen(
                                tab = activeBottomTab,
                                items = if (activeBottomTab == "history") history else starred,
                                showClearAll = activeBottomTab == "history" && history.isNotEmpty(),
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
                                    Toast.makeText(context, "Deleted from history", Toast.LENGTH_SHORT).show()
                                },
                                onSpeak = { item ->
                                    val trgLocale = Locale.forLanguageTag(Language.getByCode(item.targetLangCode).languageTag)
                                    ttsHelper.speak(item.translatedText, trgLocale)
                                },
                                onToggleStar = { item -> viewModel.toggleStarred(item) },
                            )
                        }
                    }

                    // 3. Compact Bottom Interface Dock containing Microphone and Navigation Controls
                    BottomNavBar(
                        activeBottomTab = activeBottomTab,
                        onSelectTab = { activeBottomTab = it },
                        showMicRow = activeBottomTab == "translate",
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
