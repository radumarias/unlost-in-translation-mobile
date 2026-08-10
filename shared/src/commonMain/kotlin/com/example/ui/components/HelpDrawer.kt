package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.i18n.LocalStrings
import com.example.ui.i18n.Strings

/** One collapsible entry in the help list. */
private class HelpSection(val title: String, val short: String, val body: String)

/**
 * What the app does, in the app. Ported from the web build's help drawer, but
 * the text is rewritten rather than copied: several of the web build's sections
 * describe behaviour that was deliberately not ported — most obviously
 * auto-detect, which overwrites the picker there and is sticky here.
 *
 * Sections are gated on the same [com.example.platform.PlatformCapabilities]
 * flags as the features they describe, so the help never explains a button the
 * user cannot see (design §8): no microphone section on a browser without
 * speech recognition, no dual-headset section on web.
 */
@Composable
fun HelpDrawer(
    visible: Boolean,
    showVoiceInput: Boolean,
    showImageInput: Boolean,
    showDualHeadset: Boolean,
    showRemoteConversation: Boolean = false,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val strings = LocalStrings.current
    val sections = remember(strings, showVoiceInput, showImageInput, showDualHeadset) {
        helpSections(strings, showVoiceInput, showImageInput, showDualHeadset, showRemoteConversation)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("help_drawer"),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = strings.helpTitle,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = strings.close)
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(sections) { section -> HelpSectionCard(section) }
                }
            }
        }
    }
}

@Composable
private fun HelpSectionCard(section: HelpSection) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = section.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // The one-liner is the collapsed state's whole content, so
                    // it goes away once the body it summarises is on screen.
                    AnimatedVisibility(
                        visible = !expanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = section.short,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = section.body,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The sections, in the order a new user meets the features. Split out of the
 * composable so the capability gating is a plain list filter and can be read at
 * a glance.
 */
private fun helpSections(
    strings: Strings,
    showVoiceInput: Boolean,
    showImageInput: Boolean,
    showDualHeadset: Boolean,
    showRemoteConversation: Boolean,
): List<HelpSection> = buildList {
    if (showVoiceInput) {
        add(HelpSection(strings.helpVoiceTitle, strings.helpVoiceShort, strings.helpVoiceBody))
    }
    add(HelpSection(strings.helpCulturalTitle, strings.helpCulturalShort, strings.helpCulturalBody))
    add(HelpSection(strings.helpToneTitle, strings.helpToneShort, strings.helpToneBody))
    if (showImageInput) {
        add(HelpSection(strings.helpCameraTitle, strings.helpCameraShort, strings.helpCameraBody))
    }
    add(HelpSection(strings.helpAutoDetectTitle, strings.helpAutoDetectShort, strings.helpAutoDetectBody))
    add(HelpSection(strings.helpConversationTitle, strings.helpConversationShort, strings.helpConversationBody))
    if (showRemoteConversation) {
        // Placed right after conversation mode, because it is the same feature with
        // a second phone — and it is the section that says plainly what the
        // encryption does and does not hide.
        add(HelpSection(strings.helpTwoPhonesTitle, strings.helpTwoPhonesShort, strings.helpTwoPhonesBody))
    }
    if (showDualHeadset) {
        add(HelpSection(strings.helpDualHeadsetTitle, strings.helpDualHeadsetShort, strings.helpDualHeadsetBody))
    }
    add(HelpSection(strings.helpFullScreenTitle, strings.helpFullScreenShort, strings.helpFullScreenBody))
}
