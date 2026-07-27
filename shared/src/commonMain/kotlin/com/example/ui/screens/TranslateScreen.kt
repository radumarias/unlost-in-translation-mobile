package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Language
import com.example.model.Translation
import com.example.platform.PlatformActions
import com.example.platform.SpeechSynthesizer
import com.example.ui.components.ToneSituationBar
import com.example.ui.viewmodel.TranslateViewModel
import com.example.ui.viewmodel.TranslationState
import com.example.ui.viewmodel.ValidationState

/**
 * Translate-tab workspace: the input card (text field + mic/speak/translate
 * action bar) and the animated result card (translation, round-trip check,
 * cultural-validation banner, and speak/copy/share tools).
 *
 * In commonMain (design §1). Platform touch goes through seams: TTS via
 * [speechSynthesizer] ([SpeechSynthesizer.speak] takes a BCP-47 tag directly, no
 * java.util.Locale), clipboard/share via [platformActions], and status toasts via
 * `viewModel.showMessage` (a snackbar host consumes them). The keyboard controller
 * is re-derived locally; tapping the validation banner is surfaced through
 * [onShowValidationDetails].
 */
@Composable
fun TranslateScreen(
    viewModel: TranslateViewModel,
    sourceText: String,
    sourceLang: Language,
    targetLang: Language,
    translationState: TranslationState,
    validationState: ValidationState,
    history: List<Translation>,
    starred: List<Translation>,
    tone: String,
    situation: String,
    isReadingImage: Boolean,
    speechSynthesizer: SpeechSynthesizer,
    platformActions: PlatformActions,
    showVoiceInput: Boolean,
    extendedGlyphs: Boolean,
    launchVoiceInput: (isConversationA: Boolean?, language: Language) -> Unit,
    onShowValidationDetails: (ValidationState.Success) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    // Translate Tab Screen Workspace
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // A0. Tone / situation constraints (ported from the web build)
        ToneSituationBar(
            tone = tone,
            situation = situation,
            extendedGlyphs = extendedGlyphs,
            onSelectTone = { viewModel.setTone(it) },
            onSelectSituation = { viewModel.setSituation(it) },
        )

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
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
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
                        // Voice input only (design §8 capability gating); hidden on web.
                        if (showVoiceInput) {
                            IconButton(
                                onClick = { launchVoiceInput(null, sourceLang) },
                                enabled = sourceLang.languageTag.isNotBlank(),
                            ) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = "Record Speech",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                val dualState = viewModel.dualHeadsetState.value
                                val targetDevice = if (dualState.isDualHeadsetModeEnabled) dualState.deviceA else null
                                speechSynthesizer.speak(sourceText, sourceLang.languageTag, targetDevice)
                            },
                            // An unresolved Auto-detect source has no BCP-47
                            // tag; there is no voice to read it in.
                            enabled = sourceText.isNotBlank() && sourceLang.languageTag.isNotBlank()
                        ) {
                            Icon(
                                Icons.Default.VolumeUp,
                                contentDescription = "Hear input",
                                tint = if (sourceText.isNotBlank() && sourceLang.languageTag.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
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
            // Also while a photo is being read: the transcription that fills
            // the input box only arrives with the answer.
            visible = sourceText.isNotBlank() || isReadingImage,
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
                                    // "Translating..." under a photo that has
                                    // not been read yet would be a lie.
                                    if (isReadingImage) "Reading image..." else "Translating...",
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
                                                viewModel.showMessage("Added to star history.")
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

                                val roundTripText = state.roundTripText
                                if (roundTripText != null) {
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
                                            Text(text = roundTripText, fontSize = 14.sp)
                                        }
                                    }
                                }

                                // The round trip above shows the words coming
                                // back; this shows the intent landing. It rides
                                // on the validation call, so it costs nothing.
                                val sanityCheck = (validationState as? ValidationState.Success)?.sanityCheck
                                if (!sanityCheck.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Visibility, contentDescription = "Perceived meaning", modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(text = "They will read it as:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = sanityCheck,
                                                fontSize = 14.sp,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis,
                                            )
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
                                            val successContainerColor = if (isSystemInDarkTheme()) Color(0xFF1B5E20).copy(alpha=0.5f) else Color(0xFFE8F5E9)
                                            val onSuccessContainerColor = if (isSystemInDarkTheme()) Color(0xFFA5D6A7) else Color(0xFF1B5E20)

                                            Column {
                                                Spacer(modifier = Modifier.height(12.dp))
                                                Card(
                                                    onClick = { onShowValidationDetails(vs.copy(explanation = displayExplanation)) },
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
                                                            overflow = TextOverflow.Ellipsis,
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
                                            speechSynthesizer.speak(state.translatedText, targetLang.languageTag, targetDevice)
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
                                            platformActions.copyToClipboard(state.translatedText)
                                            viewModel.showMessage("Copied translation!")
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
                                            platformActions.share(state.translatedText)
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
}
