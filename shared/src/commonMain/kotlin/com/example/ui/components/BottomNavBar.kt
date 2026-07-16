package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compact bottom dock: the mic action row (translate tab only) plus the three
 * navigation tabs. Extracted verbatim from MainAppScreen; the three previously
 * duplicated tab blocks are collapsed into [TabItem] (DRY, no visual change).
 */
@Composable
fun BottomNavBar(
    activeBottomTab: String,
    onSelectTab: (String) -> Unit,
    showMicRow: Boolean,
    showVoiceInput: Boolean,
    onConversationMode: () -> Unit,
    onMic: () -> Unit,
    sourceTextBlank: Boolean,
    onQuickEdit: () -> Unit,
) {
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
            if (showMicRow) {
                // Microphone action bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    // spacedBy keeps the same 36dp gaps as the old manual Spacers on
                    // Android, and stays balanced on web where the mic is hidden.
                    horizontalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Button (Conversation Split mode launcher)
                    IconButton(
                        onClick = onConversationMode,
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

                    // Center Circle Mic Button — voice input only (design §8 capability
                    // gating). Hidden on web (capabilities.voiceInput = false).
                    if (showVoiceInput) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(56.dp)
                                .background(MaterialTheme.colorScheme.primary, shape = CircleShape)
                                .clickable { onMic() }
                                .testTag("main_mic_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Record Speech",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    // Right Button (Quick keyboard edit / close)
                    IconButton(
                        onClick = onQuickEdit,
                        modifier = Modifier
                            .size(42.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = if (!sourceTextBlank) Icons.Default.Close else Icons.Default.Edit,
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
                TabItem(
                    tabId = "translate",
                    label = "Translate",
                    icon = Icons.Default.Translate,
                    contentDescription = "Translate Tab Button",
                    activeBottomTab = activeBottomTab,
                    onSelectTab = onSelectTab,
                )
                TabItem(
                    tabId = "history",
                    label = "History",
                    icon = Icons.Default.History,
                    contentDescription = "History Tab Button",
                    activeBottomTab = activeBottomTab,
                    onSelectTab = onSelectTab,
                )
                TabItem(
                    tabId = "saved",
                    label = "Saved",
                    icon = Icons.Default.Star,
                    contentDescription = "Saved Tab Button",
                    activeBottomTab = activeBottomTab,
                    onSelectTab = onSelectTab,
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tabId: String,
    label: String,
    icon: ImageVector,
    contentDescription: String,
    activeBottomTab: String,
    onSelectTab: (String) -> Unit,
) {
    val selected = activeBottomTab == tabId
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onSelectTab(tabId) }
            .padding(4.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                )
                .padding(horizontal = 24.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.61f),
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.61f)
        )
    }
}
