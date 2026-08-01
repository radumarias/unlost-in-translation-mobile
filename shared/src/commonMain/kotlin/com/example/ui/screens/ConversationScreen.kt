package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.DualHeadsetEntry
import com.example.model.Language
import com.example.model.flagLabel
import com.example.platform.AudioOutputCatalog
import com.example.platform.SpeechSynthesizer
import com.example.ui.components.ConversationDraftDialog
import com.example.ui.components.ToneSituationBar
import com.example.ui.i18n.LocalStrings
import com.example.ui.i18n.withArg
import com.example.ui.viewmodel.TranslateViewModel

/**
 * Conversation split-screen: bilingual message flow plus the two-party mic deck,
 * the dual-headset setup entry point, and the draft-confirmation dialog.
 *
 * In commonMain (design §1). TTS goes through [speechSynthesizer] (BCP-47 tag,
 * no java.util.Locale) and status toasts through `viewModel.showMessage`; the
 * dual-headset device selection stays behind the [audioOutputCatalog] seam.
 */
@Composable
fun ConversationScreen(
    viewModel: TranslateViewModel,
    sourceLang: Language,
    targetLang: Language,
    speechSynthesizer: SpeechSynthesizer,
    audioOutputCatalog: AudioOutputCatalog,
    showVoiceInput: Boolean,
    showDualHeadset: Boolean,
    extendedGlyphs: Boolean,
    launchVoiceInput: (isConversationA: Boolean?, language: Language) -> Unit,
    onShowFullScreen: (String) -> Unit = {},
) {
    val strings = LocalStrings.current
    val conversationHistory by viewModel.conversationHistory.collectAsState()
    val tone by viewModel.tone.collectAsState()
    val situation by viewModel.situation.collectAsState()

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
                Icon(Icons.Default.ArrowBack, contentDescription = strings.back)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = strings.conversationMode,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.weight(1f))
            DualHeadsetEntry(
                viewModel = viewModel,
                catalog = audioOutputCatalog,
                show = showDualHeadset
            )
        }

        // Tone / situation constraints — they shape every turn of the conversation
        ToneSituationBar(
            tone = tone,
            situation = situation,
            extendedGlyphs = extendedGlyphs,
            onSelectTone = { viewModel.setTone(it) },
            onSelectSituation = { viewModel.setSituation(it) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Showing the bubble to the other person is the
                                // whole point of conversation mode, so the
                                // flashcard belongs on every turn.
                                IconButton(
                                    onClick = { onShowFullScreen(item.translated) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Fullscreen,
                                        contentDescription = strings.showFullScreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        val l = if (item.speakA) targetLang else sourceLang
                                        val d = if (viewModel.dualHeadsetState.value.isDualHeadsetModeEnabled) {
                                            if (item.speakA) viewModel.dualHeadsetState.value.deviceB else viewModel.dualHeadsetState.value.deviceA
                                        } else null
                                        speechSynthesizer.speak(item.translated, l.languageTag, d)
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.VolumeUp,
                                        contentDescription = strings.speakTranslation,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
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
                // One SpeakerInput per party: mic FAB when voice input is available,
                // typed input field otherwise (web MVP: plan §7 Phase 3 acceptance —
                // "conversation mode works via typed input"). Both feed the same
                // prepareConversationPart → draft-confirmation flow.
                SpeakerInput(
                    language = sourceLang,
                    isLanguageA = true,
                    showVoiceInput = showVoiceInput,
                    extendedGlyphs = extendedGlyphs,
                    onVoiceClick = { launchVoiceInput(true, sourceLang) },
                    onSubmit = { viewModel.prepareConversationPart(it, isLanguageA = true) }
                )

                SpeakerInput(
                    language = targetLang,
                    isLanguageA = false,
                    showVoiceInput = showVoiceInput,
                    extendedGlyphs = extendedGlyphs,
                    onVoiceClick = { launchVoiceInput(false, targetLang) },
                    onSubmit = { viewModel.prepareConversationPart(it, isLanguageA = false) }
                )
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
                        val userStr = if (isA) strings.user2 else strings.user1
                        viewModel.showMessage(strings.playingTranslationToHeadset.withArg(userStr))
                    }
                    val targetLanguageTag = if (isA) targetLang.languageTag else sourceLang.languageTag
                    val targetDevice = if (dualState.isDualHeadsetModeEnabled) {
                        if (isA) dualState.deviceB else dualState.deviceA
                    } else null
                    speechSynthesizer.speak(translated, targetLanguageTag, targetDevice)
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
}

/**
 * One party's input slot on the conversation deck: language label above either
 * the mic FAB (voice input available) or a typed-input field (web MVP). Both
 * feed the same prepareConversationPart → draft-confirmation flow.
 */
@Composable
private fun RowScope.SpeakerInput(
    language: Language,
    isLanguageA: Boolean,
    showVoiceInput: Boolean,
    extendedGlyphs: Boolean,
    onVoiceClick: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val strings = LocalStrings.current
    val party = if (isLanguageA) "a" else "b"
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (showVoiceInput) Modifier else Modifier.weight(1f).padding(horizontal = 8.dp)
    ) {
        Text(
            text = language.name + " " + language.flagLabel(extendedGlyphs),
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (showVoiceInput) {
            FloatingActionButton(
                onClick = onVoiceClick,
                containerColor = if (isLanguageA) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.testTag("mic_party_$party")
            ) {
                Icon(Icons.Default.Mic, contentDescription = if (isLanguageA) strings.micA else strings.micB)
            }
        } else {
            ConversationTypedInput(
                languageName = language.name,
                testTag = "typed_input_party_$party",
                onSubmit = onSubmit
            )
        }
    }
}

/**
 * Typed replacement for a speaker's mic FAB when voice input is unavailable
 * (web MVP). Submitting hands the text to the same draft-confirmation pipeline
 * that voice recognition feeds.
 */
@Composable
private fun ConversationTypedInput(
    languageName: String,
    testTag: String,
    onSubmit: (String) -> Unit,
) {
    val strings = LocalStrings.current
    var text by remember { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onSubmit(text.trim())
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(strings.typeInLanguage.withArg(languageName), fontSize = 14.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { submit() }),
        trailingIcon = {
            IconButton(
                onClick = submit,
                enabled = text.isNotBlank()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = strings.sendLanguageMessage.withArg(languageName))
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    )
}
