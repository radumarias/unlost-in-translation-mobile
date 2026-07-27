package com.example.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.viewmodel.ConversationDraft
import com.example.ui.viewmodel.ValidationState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDraftDialog(
    draft: ConversationDraft,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onReplaceSource: (String) -> Unit,
    sourceLangName: String,
    targetLangName: String
) {
    var showDetailsState by remember { mutableStateOf<Boolean>(false) }

    Dialog(onDismissRequest = onCancel) {
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

                    val roundTrip = draft.roundTripText
                    if (roundTrip != null && roundTrip != draft.sourceText) {
                        Text("Translates back as:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = roundTrip, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, fontStyle = FontStyle.Italic)
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Validation panel
                    when (val vs = draft.validationState) {
                        is ValidationState.Loading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Validating cultural meaning...", fontSize = 12.sp)
                            }
                        }
                        is ValidationState.Success -> {
                                val displayExplanation = vs.explanation.takeIf { it.isNotBlank() } ?: "The translation is accurate. No significant cultural nuances or warnings detected."
                                val successContainerColor = if (isSystemInDarkTheme()) Color(0xFF1B5E20).copy(alpha=0.5f) else Color(0xFFE8F5E9)
                                val onSuccessContainerColor = if (isSystemInDarkTheme()) Color(0xFFA5D6A7) else Color(0xFF1B5E20)
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
                                            overflow = TextOverflow.Ellipsis
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
                        is ValidationState.Error -> {
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

    if (showDetailsState && draft.validationState is ValidationState.Success) {
        val vs = draft.validationState as ValidationState.Success
        Dialog(
            onDismissRequest = { showDetailsState = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
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

                    LazyColumn(
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

                            // Ported with the validation call: how the other
                            // party will actually read this, and any idiom in
                            // the original. Both cost no extra request.
                            if (vs.sanityCheck.isNotBlank()) {
                                DraftDetailSection("How they will read it", vs.sanityCheck)
                            }
                            vs.idiomExplanation?.let { DraftDetailSection("Idiom or local expression", it) }
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

/** A titled paragraph in the draft's validation-details dialog. */
@Composable
private fun DraftDetailSection(title: String, body: String) {
    Text(
        text = title,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = body,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(32.dp))
}
