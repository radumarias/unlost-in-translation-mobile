package com.example.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.DualHeadsetSetupDialog
import com.example.model.Language
import com.example.speech.TTSHelper
import com.example.ui.components.ConversationDraftDialog
import com.example.ui.viewmodel.TranslateViewModel

/**
 * Conversation split-screen: bilingual message flow plus the two-party mic deck,
 * the dual-headset setup entry point, and the draft-confirmation dialog.
 *
 * Extracted verbatim from MainAppScreen's `isConversationMode` branch. It still
 * takes the [viewModel], [ttsHelper] and [context] directly (VM injection and
 * the platform-service abstractions land in later Phase 1 tasks); TTS routing
 * and dual-headset device selection are kept unchanged.
 */
@Composable
fun ConversationScreen(
    viewModel: TranslateViewModel,
    sourceLang: Language,
    targetLang: Language,
    ttsHelper: TTSHelper,
    context: Context,
    launchVoiceInput: (isConversationA: Boolean?, language: Language) -> Unit,
) {
    val conversationHistory by viewModel.conversationHistory.collectAsState()

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
                        onClick = { launchVoiceInput(true, sourceLang) },
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
                        onClick = { launchVoiceInput(false, targetLang) },
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
}
