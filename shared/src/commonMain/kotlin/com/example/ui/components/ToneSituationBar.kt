package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Preset
import com.example.model.Situation
import com.example.model.Tone
import com.example.model.byId
import com.example.ui.i18n.LocalStrings
import com.example.ui.i18n.Strings
import com.example.ui.i18n.withArg

/**
 * The tone and situation selectors ported from the web build. Two dropdown
 * pills, styled after [LanguageBar]'s language pills but shorter; a non-default
 * selection is tinted so an active constraint is visible at a glance.
 *
 * [extendedGlyphs] gates the preset emoji the same way flags are gated — the
 * wasm canvas has no emoji font (design §8), so there the labels stand alone.
 */
@Composable
fun ToneSituationBar(
    tone: String,
    situation: String,
    extendedGlyphs: Boolean,
    onSelectTone: (String) -> Unit,
    onSelectSituation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PresetChip(
            presets = Tone.presets,
            selectedId = tone,
            defaultId = Tone.AUTO,
            label = strings.toneLabel,
            testTag = "tone_chip",
            extendedGlyphs = extendedGlyphs,
            onSelect = onSelectTone,
        )
        PresetChip(
            presets = Situation.presets,
            selectedId = situation,
            defaultId = Situation.GENERAL,
            label = strings.situationLabel,
            testTag = "situation_chip",
            extendedGlyphs = extendedGlyphs,
            onSelect = onSelectSituation,
        )
    }
}

@Composable
private fun RowScope.PresetChip(
    presets: List<Preset>,
    selectedId: String,
    defaultId: String,
    label: String,
    testTag: String,
    extendedGlyphs: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val strings = LocalStrings.current
    val selected = presets.byId(selectedId)
    val isActive = selectedId != defaultId

    Box(modifier = Modifier.weight(1f)) {
        Card(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .testTag(testTag),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = presetLabel(selected, extendedGlyphs, strings),
                    fontSize = 12.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = strings.selectPreset.withArg(label),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            presets.forEach { preset ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = presetLabel(preset, extendedGlyphs, strings),
                            fontWeight = if (preset.id == selectedId) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(preset.id)
                    },
                )
            }
        }
    }
}

private fun presetLabel(preset: Preset, extendedGlyphs: Boolean, strings: Strings): String {
    val label = strings.preset(preset.id)
    return if (extendedGlyphs) "${preset.icon} $label" else label
}
