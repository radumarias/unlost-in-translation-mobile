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
import com.example.model.Language
import com.example.speech.TTSHelper
import com.example.ui.components.LanguagePickerDialog
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.TranslationState
import com.example.ui.viewmodel.ValidationState
import com.example.ui.viewmodel.TranslateViewModel
import com.example.wear.WearSyncManager

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
    val viewModel: TranslateViewModel = viewModel()
    
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
    val conversationHistory by viewModel.conversationHistory.collectAsState()

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
            Toast.makeText(context, "Listening on $userStr's headset...", Toast.LENGTH_SHORT).show()
        }
        
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.locale.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.locale.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, language.locale.toLanguageTag())
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(language.locale.toLanguageTag()))
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now (${language.name})...")
            }
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Voice input is not supported on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    // Watch sync triggering
    LaunchedEffect(translationState) {
        val state = translationState
        if (state is TranslationState.Success) {
            WearSyncManager.syncTranslationToWear(
                context,
                state.originalText,
                state.translatedText,
                sourceLang,
                targetLang
            )
            WearSyncManager.sendWearableNotification(
                context,
                state.originalText,
                state.translatedText,
                sourceLang,
                targetLang
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isConversationMode) {
                // --- Conversation Split-Screen Panel ---
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Conversation Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.setConversationMode(false) }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Conversation Mode",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        val dualHeadsetState by viewModel.dualHeadsetState.collectAsState()
                        var showDualHeadsetSetup by remember { mutableStateOf(false) }
                        IconButton(onClick = { showDualHeadsetSetup = true }) {
                            Icon(
                                Icons.Default.Headphones, 
                                contentDescription = "Dual Headset Setup",
                                tint = if (dualHeadsetState.isDualHeadsetModeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (showDualHeadsetSetup) {
                            DualHeadsetSetupDialog(
                                viewModel = viewModel,
                                onDismiss = { showDualHeadsetSetup = false }
                            )
                        }
                    }

                    // Conversational History Message Flow
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        reverseLayout = true
                    ) {
                        items(conversationHistory.reversed()) { item ->
                            val bubbleColor = if (item.speakA) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            }
                            val alignment = if (item.speakA) Alignment.Start else Alignment.End

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalAlignment = alignment
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = bubbleColor),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.widthIn(max = 280.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = item.text,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = item.translated,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        IconButton(
                                            onClick = {
                                                val l = if (item.speakA) targetLang else sourceLang
                                                val d = if (viewModel.dualHeadsetState.value.isDualHeadsetModeEnabled) {
                                                    if (item.speakA) viewModel.dualHeadsetState.value.deviceB else viewModel.dualHeadsetState.value.deviceA
                                                } else null
                                                ttsHelper.speak(item.translated, l.locale, d)
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.VolumeUp,
                                                contentDescription = "Speak translation",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Lower Action Control Deck for dual parties
                    Surface(
                        tonalElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Speaker A (Source)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = sourceLang.name + " " + sourceLang.flag,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                FloatingActionButton(
                                    onClick = { launchVoiceInput(isConversationA = true, sourceLang) },
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("mic_party_a")
                                ) {
                                    Icon(Icons.Default.Mic, contentDescription = "Mic A")
                                }
                            }

                            // Speaker B (Target)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = targetLang.name + " " + targetLang.flag,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                FloatingActionButton(
                                    onClick = { launchVoiceInput(isConversationA = false, targetLang) },
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.testTag("mic_party_b")
                                ) {
                                    Icon(Icons.Default.Mic, contentDescription = "Mic B")
                                }
                            }
                        }
                    }
                }
                
                val draft by viewModel.conversationDraft.collectAsState()
                if (draft != null) {
                    val sName = if (draft!!.isLanguageA) sourceLang.name else targetLang.name
                    val tName = if (draft!!.isLanguageA) targetLang.name else sourceLang.name
                    ConversationDraftDialog(
                        draft = draft!!,
                        onConfirm = {
                            viewModel.confirmConversationDraft { translated, isA ->
                                val dualState = viewModel.dualHeadsetState.value
                                if (dualState.isDualHeadsetModeEnabled) {
                                    val userStr = if (isA) "User 2" else "User 1"
                                    Toast.makeText(context, "Playing translation to $userStr's headset...", Toast.LENGTH_SHORT).show()
                                }
                                val targetLocale = if (isA) targetLang.locale else sourceLang.locale
                                val targetDevice = if (dualState.isDualHeadsetModeEnabled) {
                                    if (isA) dualState.deviceB else dualState.deviceA
                                } else null
                                ttsHelper.speak(translated, targetLocale, targetDevice)
                            }
                        },
                        onCancel = { viewModel.cancelConversationDraft() },
                        onReplaceSource = { newSourceText ->
                            viewModel.cancelConversationDraft()
                            viewModel.prepareConversationPart(newSourceText, isLanguageA = draft!!.isLanguageA)
                        },
                        sourceLangName = sName,
                        targetLangName = tName
                    )
                }

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
                            // Top branding line with AI toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Translate,
                                        contentDescription = "Logo",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Translate",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val dualHeadsetState by viewModel.dualHeadsetState.collectAsState()
                                    var showDualHeadsetSetup by remember { mutableStateOf(false) }
                                    IconButton(onClick = { showDualHeadsetSetup = true }) {
                                        Icon(
                                            Icons.Default.Headphones, 
                                            contentDescription = "Dual Headset Setup",
                                            tint = if (dualHeadsetState.isDualHeadsetModeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (showDualHeadsetSetup) {
                                        DualHeadsetSetupDialog(
                                            viewModel = viewModel,
                                            onDismiss = { showDualHeadsetSetup = false }
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.width(4.dp))

                                    // AI Translate Engine Toggle (Styled beautifully with slate color overlays)
                                    Card(
                                    onClick = { viewModel.setAiEnhanced(!isAiEnhanced) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isAiEnhanced) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (isAiEnhanced) Icons.Default.AutoAwesome else Icons.Default.SettingsSuggest,
                                            contentDescription = "AI Mode",
                                            tint = if (isAiEnhanced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isAiEnhanced) "Gemini Translation" else "Standard Engine",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isAiEnhanced) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                }
                            }

                            // Dual horizontal language selector pills with swap icon
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Source Lang Button
                                Card(
                                    onClick = { choosingSourceLanguage = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .testTag("lang_src_button"),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Text(text = sourceLang.flag + " ", fontSize = 16.sp)
                                        Text(
                                            text = sourceLang.name,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 13.sp,
                                            maxLines = 1
                                        )
                                        Icon(
                                            Icons.Default.ArrowDropDown,
                                            contentDescription = "Select language",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                // Swap Languages IconButton
                                IconButton(
                                    onClick = { viewModel.swapLanguages() },
                                    modifier = Modifier
                                        .padding(horizontal = 6.dp)
                                        .testTag("swap_langs_btn")
                                        .size(36.dp)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), shape = CircleShape)
                                ) {
                                    Icon(
                                        Icons.Default.SwapHoriz,
                                        contentDescription = "Swap Languages",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Target Lang Button
                                Card(
                                    onClick = { choosingTargetLanguage = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .testTag("lang_target_button"),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Text(text = targetLang.flag + " ", fontSize = 16.sp)
                                        Text(
                                            text = targetLang.name,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 13.sp,
                                            maxLines = 1
                                        )
                                        Icon(
                                            Icons.Default.ArrowDropDown,
                                            contentDescription = "Select target language",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
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
                            // Translate Tab Screen Workspace
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // A. Input Box Card (White, border-slate-100, corner 28dp, shadow-sm)
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(28.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(18.dp)
                                    ) {
                                        Box(modifier = Modifier.fillMaxWidth()) {
                                            OutlinedTextField(
                                                value = sourceText,
                                                onValueChange = { viewModel.setSourceText(it) },
                                                placeholder = { 
                                                    Text(
                                                        "Enter text to translate...", 
                                                        fontSize = 16.sp, 
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                    ) 
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .testTag("translation_input_field"),
                                                minLines = 3,
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = Color.Transparent,
                                                    unfocusedBorderColor = Color.Transparent
                                                ),
                                                textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface),
                                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                                                    keyboardController?.hide()
                                                    viewModel.triggerTranslation()
                                                })
                                            )

                                            if (sourceText.isNotEmpty()) {
                                                IconButton(
                                                    onClick = { viewModel.setSourceText("") },
                                                    modifier = Modifier
                                                        .align(Alignment.TopEnd)
                                                        .size(24.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Close,
                                                        contentDescription = "Clear",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        // Action bar inside input box
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(
                                                    onClick = { launchVoiceInput(null, sourceLang) }
                                                ) {
                                                    Icon(
                                                        Icons.Default.Mic,
                                                        contentDescription = "Record Speech",
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }

                                                IconButton(
                                                    onClick = { 
                                                        val dualState = viewModel.dualHeadsetState.value
                                                        val targetDevice = if (dualState.isDualHeadsetModeEnabled) dualState.deviceA else null
                                                        ttsHelper.speak(sourceText, sourceLang.locale, targetDevice) 
                                                    },
                                                    enabled = sourceText.isNotBlank()
                                                ) {
                                                    Icon(
                                                        Icons.Default.VolumeUp,
                                                        contentDescription = "Hear input",
                                                        tint = if (sourceText.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                                    )
                                                }
                                            }

                                            Button(
                                                onClick = {
                                                    keyboardController?.hide()
                                                    viewModel.triggerTranslation()
                                                },
                                                enabled = sourceText.isNotBlank() || translationState is TranslationState.Loading,
                                                modifier = Modifier.testTag("translate_submit_btn"),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Text("Translate", fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }

                                // B. Output Card Block (Animated, bg-#D3E3FD, corner 28dp, navy style)
                                AnimatedVisibility(
                                    visible = sourceText.isNotBlank(),
                                    enter = fadeIn() + expandVertically(),
                                    exit = fadeOut() + shrinkVertically()
                                ) {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("translation_result_card"),
                                        shape = RoundedCornerShape(28.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(20.dp)
                                        ) {
                                            when (val state = translationState) {
                                                is TranslationState.Loading -> {
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(20.dp),
                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                    ) {
                                                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimaryContainer)
                                                        Spacer(modifier = Modifier.height(10.dp))
                                                        Text(
                                                            "Translating...", 
                                                            style = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                                        )
                                                    }
                                                }

                                                is TranslationState.Success -> {
                                                    Column {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = targetLang.name.uppercase(),
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.secondary,
                                                                letterSpacing = 1.sp
                                                            )

                                                            IconButton(
                                                                onClick = {
                                                                    // Toggle star logic
                                                                    val existingEntity = history.firstOrNull {
                                                                        it.sourceText == state.originalText &&
                                                                        it.translatedText == state.translatedText
                                                                    }
                                                                    if (existingEntity != null) {
                                                                        viewModel.toggleStarred(existingEntity)
                                                                    } else {
                                                                        Toast.makeText(context, "Added to star history.", Toast.LENGTH_SHORT).show()
                                                                    }
                                                                },
                                                                modifier = Modifier.size(24.dp)
                                                            ) {
                                                                val starredList = starred.map { it.sourceText to it.translatedText }
                                                                val isStarred = starredList.contains(state.originalText to state.translatedText)
                                                                Icon(
                                                                    imageVector = if (isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                                                                    contentDescription = "Favorite",
                                                                    tint = if (isStarred) Color(0xFFFFD700) else MaterialTheme.colorScheme.onPrimaryContainer,
                                                                    modifier = Modifier.size(20.dp)
                                                                )
                                                            }
                                                        }

                                                        Spacer(modifier = Modifier.height(10.dp))

                                                        Text(
                                                            text = state.translatedText,
                                                            fontSize = 20.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                            modifier = Modifier.testTag("translated_result_text")
                                                        )

                                                        if (state.roundTripText != null) {
                                                            Spacer(modifier = Modifier.height(12.dp))
                                                            Card(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
                                                                shape = RoundedCornerShape(12.dp)
                                                            ) {
                                                                Column(modifier = Modifier.padding(12.dp)) {
                                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                                        Icon(Icons.Default.Repeat, contentDescription = "Round trip", modifier = Modifier.size(16.dp))
                                                                        Spacer(modifier = Modifier.width(6.dp))
                                                                        Text(text = "Translates back as:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                                    }
                                                                    Spacer(modifier = Modifier.height(6.dp))
                                                                    Text(text = state.roundTripText, fontSize = 14.sp)
                                                                }
                                                            }
                                                        }

                                                        AnimatedContent(
                                                            targetState = validationState,
                                                            transitionSpec = {
                                                                (fadeIn(animationSpec = tween(300)) + slideInVertically(animationSpec = tween(300)) { 20 }).togetherWith(fadeOut(animationSpec = tween(300)))
                                                            },
                                                            label = "ValidationState"
                                                        ) { vs ->
                                                            when (vs) {
                                                                is ValidationState.Loading -> {
                                                                    Column {
                                                                        Spacer(modifier = Modifier.height(12.dp))
                                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                                            Spacer(modifier = Modifier.width(8.dp))
                                                                            Text("Validating cultural meaning...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                                                        }
                                                                    }
                                                                }
                                                                is ValidationState.Success -> {
                                                                    val hasWarning = vs.isWarning
                                                                    val displayExplanation = vs.explanation.takeIf { it.isNotBlank() } ?: "The translation is accurate. No significant cultural nuances or warnings detected."
                                                                    val successContainerColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF1B5E20).copy(alpha=0.5f) else Color(0xFFE8F5E9)
                                                                    val onSuccessContainerColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFFA5D6A7) else Color(0xFF1B5E20)
                                                                    
                                                                    Column {
                                                                        Spacer(modifier = Modifier.height(12.dp))
                                                                        Card(
                                                                            onClick = { showValidationDetails = vs.copy(explanation = displayExplanation) },
                                                                            modifier = Modifier.fillMaxWidth(),
                                                                            colors = CardDefaults.cardColors(
                                                                                containerColor = if (hasWarning) MaterialTheme.colorScheme.errorContainer else successContainerColor
                                                                            ),
                                                                            shape = RoundedCornerShape(12.dp)
                                                                        ) {
                                                                            Column(modifier = Modifier.padding(12.dp)) {
                                                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                                                    Icon(
                                                                                        if (hasWarning) Icons.Default.Warning else Icons.Default.CheckCircle, 
                                                                                        contentDescription = "Context", 
                                                                                        modifier = Modifier.size(16.dp),
                                                                                        tint = if (hasWarning) MaterialTheme.colorScheme.onErrorContainer else onSuccessContainerColor
                                                                                    )
                                                                                    Spacer(modifier = Modifier.width(6.dp))
                                                                                    Text(
                                                                                        text = if (hasWarning) "Cultural Warning" else "Cultural Context", 
                                                                                        fontSize = 12.sp, 
                                                                                        fontWeight = FontWeight.Bold,
                                                                                        color = if (hasWarning) MaterialTheme.colorScheme.onErrorContainer else onSuccessContainerColor
                                                                                    )
                                                                                }
                                                                                Spacer(modifier = Modifier.height(6.dp))
                                                                                Text(
                                                                                    text = displayExplanation, 
                                                                                    fontSize = 14.sp,
                                                                                    maxLines = 2,
                                                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                                                    color = if (hasWarning) MaterialTheme.colorScheme.onErrorContainer else onSuccessContainerColor
                                                                                )
                                                                                Spacer(modifier = Modifier.height(4.dp))
                                                                                Text(
                                                                                    text = "Tap to see details and suggestions", 
                                                                                    fontSize = 11.sp, 
                                                                                    fontWeight = FontWeight.Medium,
                                                                                    color = if (hasWarning) MaterialTheme.colorScheme.onErrorContainer.copy(alpha=0.8f) else onSuccessContainerColor.copy(alpha=0.8f)
                                                                                )
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                                is ValidationState.Error -> {
                                                                    Column {
                                                                        Spacer(modifier = Modifier.height(12.dp))
                                                                        Text(text = vs.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                                                    }
                                                                }
                                                                else -> {
                                                                    Spacer(modifier = Modifier.height(0.dp))
                                                                }
                                                            }
                                                        }

                                                        Spacer(modifier = Modifier.height(16.dp))

                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.End,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            // Speaker tool
                                                            IconButton(
                                                                onClick = { 
                                                                    val dualState = viewModel.dualHeadsetState.value
                                                                    val targetDevice = if (dualState.isDualHeadsetModeEnabled) dualState.deviceB else null
                                                                    ttsHelper.speak(state.translatedText, targetLang.locale, targetDevice) 
                                                                },
                                                                modifier = Modifier
                                                                    .padding(horizontal = 4.dp)
                                                                    .size(40.dp)
                                                                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f), shape = CircleShape)
                                                            ) {
                                                                Icon(
                                                                    Icons.Default.VolumeUp,
                                                                    contentDescription = "Speak translation",
                                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            }

                                                            // Copy Clipboard
                                                            IconButton(
                                                                onClick = {
                                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                                    val clip = ClipData.newPlainText("translation", state.translatedText)
                                                                    clipboard.setPrimaryClip(clip)
                                                                    Toast.makeText(context, "Copied translation!", Toast.LENGTH_SHORT).show()
                                                                },
                                                                modifier = Modifier
                                                                    .padding(horizontal = 4.dp)
                                                                    .size(40.dp)
                                                                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f), shape = CircleShape)
                                                            ) {
                                                                Icon(
                                                                    Icons.Default.ContentCopy,
                                                                    contentDescription = "Copy text",
                                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            }

                                                            // Share tool
                                                            IconButton(
                                                                onClick = {
                                                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                                                        type = "text/plain"
                                                                        putExtra(Intent.EXTRA_TEXT, state.translatedText)
                                                                    }
                                                                    context.startActivity(Intent.createChooser(intent, "Share translation"))
                                                                },
                                                                modifier = Modifier
                                                                    .padding(horizontal = 4.dp)
                                                                    .size(40.dp)
                                                                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f), shape = CircleShape)
                                                            ) {
                                                                Icon(
                                                                    Icons.Default.Share,
                                                                    contentDescription = "Share",
                                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }

                                                is TranslationState.Error -> {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Icon(
                                                            Icons.Default.ErrorOutline,
                                                            contentDescription = "Error",
                                                            tint = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.size(24.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(
                                                            text = state.message,
                                                            color = MaterialTheme.colorScheme.error,
                                                            fontSize = 14.sp
                                                        )
                                                    }
                                                }
                                                else -> {}
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // History or Saved view (Lists)
                            val displayList = if (activeBottomTab == "history") history else starred
                            Column(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (activeBottomTab == "history") "RECENT TRANSLATIONS" else "SAVED TRANSLATIONS",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )

                                    if (activeBottomTab == "history" && history.isNotEmpty()) {
                                        TextButton(onClick = { viewModel.clearHistory() }) {
                                            Icon(
                                                Icons.Default.DeleteSweep,
                                                contentDescription = "Clear all",
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Clear All", fontSize = 11.sp)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                if (displayList.isEmpty()) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .padding(32.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = if (activeBottomTab == "history") Icons.Default.History else Icons.Default.Star,
                                            contentDescription = "Empty",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                                            modifier = Modifier.size(64.dp)
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = if (activeBottomTab == "history") "No translation history yet." else "No favorite items saved.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            fontSize = 14.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                } else {
                                    Card(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        shape = RoundedCornerShape(24.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                    ) {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(vertical = 8.dp)
                                        ) {
                                            items(displayList, key = { it.id }) { item ->
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .combinedClickable(
                                                            onClick = {
                                                                viewModel.setSourceLanguage(Language.getByCode(item.sourceLangCode))
                                                                viewModel.setTargetLanguage(Language.getByCode(item.targetLangCode))
                                                                viewModel.setSourceText(item.sourceText)
                                                                viewModel.triggerTranslation()
                                                                activeBottomTab = "translate"
                                                            },
                                                            onLongClick = {
                                                                viewModel.deleteHistoricalItem(item.id)
                                                                Toast.makeText(context, "Deleted from history", Toast.LENGTH_SHORT).show()
                                                            }
                                                        )
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            val srcLang = Language.getByCode(item.sourceLangCode)
                                                            val trgLang = Language.getByCode(item.targetLangCode)
                                                            Text(
                                                                text = "${srcLang.flag} ${srcLang.name}  ➔  ${trgLang.flag} ${trgLang.name}",
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.secondary
                                                            )
                                                            Spacer(modifier = Modifier.height(4.dp))
                                                            Text(
                                                                text = item.sourceText,
                                                                fontSize = 14.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                            Spacer(modifier = Modifier.height(2.dp))
                                                            Text(
                                                                text = item.translatedText,
                                                                fontSize = 15.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.onSurface
                                                            )
                                                        }

                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            IconButton(
                                                                onClick = {
                                                                    val trgLocale = Language.getByCode(item.targetLangCode).locale
                                                                    ttsHelper.speak(item.translatedText, trgLocale)
                                                                },
                                                                modifier = Modifier.size(32.dp)
                                                            ) {
                                                                Icon(
                                                                    Icons.Default.VolumeUp,
                                                                    contentDescription = "Speak translation",
                                                                    tint = MaterialTheme.colorScheme.primary,
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            }

                                                            IconButton(
                                                                onClick = { viewModel.toggleStarred(item) },
                                                                modifier = Modifier.size(32.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = if (item.isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                                                                    contentDescription = "Toggle favorite",
                                                                    tint = if (item.isStarred) Color(0xFFFFD700) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    HorizontalDivider(
                                                        modifier = Modifier.padding(horizontal = 16.dp),
                                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Compact Bottom Interface Dock containing Microphone and Navigation Controls
                    Surface(
                        tonalElevation = 6.dp,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .navigationBarsPadding()
                                .padding(top = 8.dp, bottom = 8.dp, start = 16.dp, end = 16.dp)
                        ) {
                            if (activeBottomTab == "translate") {
                                // Microphone action bar
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Left Button (Conversation Split mode launcher)
                                    IconButton(
                                        onClick = { viewModel.setConversationMode(true) },
                                        modifier = Modifier
                                            .size(42.dp)
                                            .background(MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape)
                                            .testTag("conversation_mode_trigger")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Hearing,
                                            contentDescription = "Conversation split-mic Mode",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(36.dp))

                                    // Center Circle Mic Button (60x60, prominent theme blue)
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(56.dp)
                                            .background(MaterialTheme.colorScheme.primary, shape = CircleShape)
                                            .clickable { launchVoiceInput(null, sourceLang) }
                                            .testTag("main_mic_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Mic,
                                            contentDescription = "Record Speech",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(36.dp))

                                    // Right Button (Quick keyboard edit / close)
                                    IconButton(
                                        onClick = { 
                                            if (sourceText.isNotBlank()) {
                                                viewModel.setSourceText("")
                                            } else {
                                                // Load a pleasant prompt
                                                viewModel.setSourceText("Hello, how are you?")
                                                viewModel.triggerTranslation()
                                            }
                                        },
                                        modifier = Modifier
                                            .size(42.dp)
                                            .background(MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = if (sourceText.isNotBlank()) Icons.Default.Close else Icons.Default.Edit,
                                            contentDescription = "Quick text input action",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }

                            // Navigation tabs with rounded active indicator pills
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Translate Tab item
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clickable { activeBottomTab = "translate" }
                                        .padding(4.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(
                                                if (activeBottomTab == "translate") MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                            )
                                            .padding(horizontal = 24.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Translate,
                                            contentDescription = "Translate Tab Button",
                                            tint = if (activeBottomTab == "translate") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.61f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Translate",
                                        fontSize = 10.sp,
                                        fontWeight = if (activeBottomTab == "translate") FontWeight.Bold else FontWeight.Medium,
                                        color = if (activeBottomTab == "translate") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.61f)
                                    )
                                }

                                // History Tab item
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clickable { activeBottomTab = "history" }
                                        .padding(4.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(
                                                if (activeBottomTab == "history") MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                            )
                                            .padding(horizontal = 24.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = "History Tab Button",
                                            tint = if (activeBottomTab == "history") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.61f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "History",
                                        fontSize = 10.sp,
                                        fontWeight = if (activeBottomTab == "history") FontWeight.Bold else FontWeight.Medium,
                                        color = if (activeBottomTab == "history") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.61f)
                                    )
                                }

                                // Saved Tab item
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clickable { activeBottomTab = "saved" }
                                        .padding(4.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(
                                                if (activeBottomTab == "saved") MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                            )
                                            .padding(horizontal = 24.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = "Saved Tab Button",
                                            tint = if (activeBottomTab == "saved") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.61f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Saved",
                                        fontSize = 10.sp,
                                        fontWeight = if (activeBottomTab == "saved") FontWeight.Bold else FontWeight.Medium,
                                        color = if (activeBottomTab == "saved") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.61f)
                                    )
                                }
                            }
                        }
                    }
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
            val lastShowValidationDetails = remember { mutableStateOf<ValidationState.Success?>(null) }
            LaunchedEffect(showValidationDetails) {
                if (showValidationDetails != null) {
                    lastShowValidationDetails.value = showValidationDetails
                }
            }
            AnimatedVisibility(
                visible = showValidationDetails != null,
                enter = expandIn(expandFrom = Alignment.Center, animationSpec = tween(300)) + fadeIn(tween(300)),
                exit = shrinkOut(shrinkTowards = Alignment.Center, animationSpec = tween(300)) + fadeOut(tween(300))
            ) {
                val vs = lastShowValidationDetails.value ?: return@AnimatedVisibility
                val successIconColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF4CAF50)
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { showValidationDetails = null },
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .fillMaxHeight(0.85f)
                            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { },
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (vs.isWarning) Icons.Default.Warning else Icons.Default.CheckCircle,
                                        contentDescription = "Details",
                                        tint = if (vs.isWarning) MaterialTheme.colorScheme.error else successIconColor,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = if (vs.isWarning) "Cultural Warning" else "Cultural Context",
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                IconButton(onClick = { showValidationDetails = null }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(28.dp))
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(24.dp))
                            
                            androidx.compose.foundation.lazy.LazyColumn(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                item {
                                    Text(
                                        text = "Explanation",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = vs.explanation,
                                        fontSize = 16.sp,
                                        lineHeight = 24.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    
                                    Spacer(modifier = Modifier.height(32.dp))
                                }
                                
                                if (vs.suggestions.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "Suggested alternatives:",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                    }
                                    
                                    items(vs.suggestions.size) { index ->
                                        val suggestion = vs.suggestions[index]
                                        Card(
                                            onClick = { 
                                                showValidationDetails = null
                                                viewModel.setSourceText(suggestion.text)
                                                keyboardController?.hide()
                                                viewModel.triggerTranslation() 
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(16.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.AutoFixHigh,
                                                    contentDescription = "Suggestion",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                                Spacer(modifier = Modifier.width(16.dp))
                                                Column {
                                                    Card(
                                                        shape = RoundedCornerShape(4.dp),
                                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                                    ) {
                                                        Text(
                                                            text = suggestion.tone.uppercase(),
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = suggestion.text,
                                                        fontSize = 16.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ConversationDraftDialog(
    draft: com.example.ui.viewmodel.ConversationDraft,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onReplaceSource: (String) -> Unit,
    sourceLangName: String,
    targetLangName: String
) {
    var showDetailsState by remember { mutableStateOf<Boolean>(false) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "Confirm Translation",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Source text
                Text("You said ($sourceLangName):", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = draft.sourceText, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                
                Spacer(modifier = Modifier.height(12.dp))

                if (draft.isTranslating) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    // Translated text
                    Text("Translation ($targetLangName):", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = draft.translatedText, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    
                    Spacer(modifier = Modifier.height(12.dp))

                    if (draft.roundTripText != null && draft.roundTripText != draft.sourceText) {
                        Text("Translates back as:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = draft.roundTripText, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Validation panel
                    when (val vs = draft.validationState) {
                        is com.example.ui.viewmodel.ValidationState.Loading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Validating cultural meaning...", fontSize = 12.sp)
                            }
                        }
                        is com.example.ui.viewmodel.ValidationState.Success -> {
                                val displayExplanation = vs.explanation.takeIf { it.isNotBlank() } ?: "The translation is accurate. No significant cultural nuances or warnings detected."
                                val successContainerColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF1B5E20).copy(alpha=0.5f) else Color(0xFFE8F5E9)
                                val onSuccessContainerColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFFA5D6A7) else Color(0xFF1B5E20)
                                val containerColor = if (vs.isWarning) MaterialTheme.colorScheme.errorContainer else successContainerColor
                                val contentColor = if (vs.isWarning) MaterialTheme.colorScheme.onErrorContainer else onSuccessContainerColor
                                
                                Surface(
                                    color = containerColor,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().clickable { showDetailsState = true }
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (vs.isWarning) Icons.Default.Warning else Icons.Default.CheckCircle,
                                                contentDescription = "Validation Info",
                                                tint = contentColor,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (vs.isWarning) "Cultural Warning" else "Cultural Context",
                                                fontWeight = FontWeight.Bold,
                                                color = contentColor,
                                                fontSize = 14.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = displayExplanation,
                                            color = contentColor,
                                            fontSize = 14.sp,
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Tap to see details and suggestions",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = contentColor.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                        }
                        is com.example.ui.viewmodel.ValidationState.Error -> {
                            Text(text = vs.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                        else -> {}
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        enabled = !draft.isTranslating
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }

    if (showDetailsState && draft.validationState is com.example.ui.viewmodel.ValidationState.Success) {
        val vs = draft.validationState
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showDetailsState = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    TopAppBar(
                        title = { Text("Cultural Details") },
                        navigationIcon = {
                            IconButton(onClick = { showDetailsState = false }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                    ) {
                        item {
                            val containerColor = if (vs.isWarning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                            val contentColor = if (vs.isWarning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = containerColor,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (vs.isWarning) Icons.Default.Warning else Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = contentColor,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = if (vs.isWarning) "Important Tone Warning" else "Cultural Context",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(24.dp))
                            
                            val displayExplanation = vs.explanation.takeIf { it.isNotBlank() } ?: "The translation is accurate. No significant cultural nuances or warnings detected."
                            if (displayExplanation.isNotBlank()) {
                                Text(
                                    text = displayExplanation,
                                    fontSize = 16.sp,
                                    lineHeight = 24.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(32.dp))
                            }
                        }
                        
                        if (vs.suggestions.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Suggested alternatives:",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                            
                            items(vs.suggestions.size) { index ->
                                val suggestion = vs.suggestions[index]
                                Card(
                                    onClick = { 
                                        showDetailsState = false
                                        onReplaceSource(suggestion.text)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = suggestion.tone,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = suggestion.text,
                                            fontSize = 16.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
