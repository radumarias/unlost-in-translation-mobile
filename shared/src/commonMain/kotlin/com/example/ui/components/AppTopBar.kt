package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.platform.AudioOutputCatalog
import com.example.ui.i18n.LocalStrings
import com.example.ui.viewmodel.TranslateViewModel

/**
 * Header top row: the "Translate" branding, the dual-headset setup entry point,
 * and the AI-engine (Gemini vs Standard) toggle.
 *
 * Extracted verbatim from MainAppScreen's translate-screen header. It still
 * takes the [viewModel] directly (VM injection and the platform-service
 * abstractions land in later Phase 1 tasks); the dual-headset dialog and engine
 * toggle wiring are unchanged.
 */
@Composable
fun AppTopBar(
    viewModel: TranslateViewModel,
    isAiEnhanced: Boolean,
    audioOutputCatalog: AudioOutputCatalog,
    showDualHeadset: Boolean,
    onShowHelp: () -> Unit = {},
) {
    val strings = LocalStrings.current
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
                contentDescription = strings.logoDescription,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = strings.appName,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onShowHelp,
                modifier = Modifier.size(32.dp).testTag("help_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = strings.helpDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            DualHeadsetEntry(
                viewModel = viewModel,
                catalog = audioOutputCatalog,
                show = showDualHeadset
            )

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
                        contentDescription = strings.aiModeDescription,
                        tint = if (isAiEnhanced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isAiEnhanced) strings.engineGemini else strings.engineStandard,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isAiEnhanced) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
