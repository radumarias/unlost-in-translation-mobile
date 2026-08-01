package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.SwapHoriz
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
import com.example.model.Language
import com.example.model.flagLabel
import com.example.ui.i18n.LocalStrings
import com.example.ui.i18n.withArg

/**
 * Header language selector: source pill, swap button, target pill.
 *
 * Extracted verbatim from MainAppScreen's translate-screen header. The swap and
 * picker-open actions are threaded as callbacks so the picker-visibility state
 * stays in MainAppScreen; the rendering is unchanged.
 */
@Composable
fun LanguageBar(
    sourceLang: Language,
    targetLang: Language,
    extendedGlyphs: Boolean,
    onSwap: () -> Unit,
    onChooseSource: () -> Unit,
    onChooseTarget: () -> Unit,
    /** What auto-detect found, shown on the source pill; null before it has. */
    detectedSourceLanguage: Language? = null,
) {
    // The selection stays "Auto-detect"; the pill reports what it resolved to
    // so the user can see the app understood them. ASCII separator: the wasm
    // canvas has no glyph for the middle dot.
    val strings = LocalStrings.current
    val resolvedSource = detectedSourceLanguage.takeIf { sourceLang.code == Language.AUTO_CODE }
    val sourceLabel = resolvedSource?.let { strings.autoDetectedLabel.withArg(it.name) } ?: sourceLang.name
    // Dual horizontal language selector pills with swap icon
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Source Lang Button
        Card(
            onClick = onChooseSource,
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
                Text(
                    text = (resolvedSource ?: sourceLang).flagLabel(extendedGlyphs) + " ",
                    fontSize = 16.sp,
                )
                Text(
                    text = sourceLabel,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    maxLines = 1
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = strings.selectSourceLanguage,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Swap Languages IconButton
        IconButton(
            onClick = onSwap,
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .testTag("swap_langs_btn")
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), shape = CircleShape)
        ) {
            Icon(
                Icons.Default.SwapHoriz,
                contentDescription = strings.swapLanguages,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }

        // Target Lang Button
        Card(
            onClick = onChooseTarget,
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
                Text(text = targetLang.flagLabel(extendedGlyphs) + " ", fontSize = 16.sp)
                Text(
                    text = targetLang.name,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    maxLines = 1
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = strings.selectTargetLanguage,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
