package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.viewmodel.RewriteState
import com.example.ui.viewmodel.ValidationState

/**
 * Full-screen cultural-validation overlay. Extracted verbatim from
 * MainAppScreen; [current] non-null triggers the expand animation, and the
 * last non-null value is retained internally so the shrink animation still
 * has content to render while dismissing (previously `lastShowValidationDetails`).
 */
@Composable
fun ValidationDetailsSheet(
    current: ValidationState.Success?,
    rewriteState: RewriteState,
    onDismiss: () -> Unit,
    onSelectSuggestion: (String) -> Unit,
    onRequestRewrite: (direction: String?) -> Unit,
    onApplyRewrite: (String) -> Unit,
) {
    var lastShown by remember { mutableStateOf<ValidationState.Success?>(null) }
    LaunchedEffect(current) {
        if (current != null) {
            lastShown = current
        }
    }
    AnimatedVisibility(
        visible = current != null,
        enter = expandIn(expandFrom = Alignment.Center, animationSpec = tween(300)) + fadeIn(tween(300)),
        exit = shrinkOut(shrinkTowards = Alignment.Center, animationSpec = tween(300)) + fadeOut(tween(300))
    ) {
        val vs = lastShown ?: return@AnimatedVisibility
        val successIconColor = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF4CAF50)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.85f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
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
                        IconButton(onClick = { onDismiss() }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(28.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    LazyColumn(
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

                        // How the other party will read it — the literal round
                        // trip lives on the result card; this is the intent.
                        if (vs.sanityCheck.isNotBlank()) {
                            item {
                                DetailSection(
                                    icon = Icons.Default.Visibility,
                                    title = "How they will read it",
                                    body = vs.sanityCheck,
                                )
                            }
                        }

                        vs.idiomExplanation?.let { idiom ->
                            item {
                                DetailSection(
                                    icon = Icons.Default.Translate,
                                    title = "Idiom or local expression",
                                    body = idiom,
                                )
                            }
                        }

                        // Rewriting only makes sense when there is something to
                        // fix, and it costs a request — so it is a button.
                        if (vs.isWarning || vs.idiomExplanation != null) {
                            item {
                                RewritePanel(
                                    state = rewriteState,
                                    onRequestRewrite = onRequestRewrite,
                                    onApplyRewrite = onApplyRewrite,
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
                                    onClick = { onSelectSuggestion(suggestion.text) },
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

/**
 * The rewrite panel: a button until the user asks, then the suggested rewrite
 * of their *own* message plus the other angles the model offered, each of which
 * re-runs the rewrite that way. Only the tapped pill spins, because
 * [RewriteState.Loading] carries which direction is in flight.
 */
@Composable
private fun RewritePanel(
    state: RewriteState,
    onRequestRewrite: (direction: String?) -> Unit,
    onApplyRewrite: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.AutoFixHigh,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Rewrite what you said",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(modifier = Modifier.height(8.dp))

    val success = state as? RewriteState.Success
    if (success == null && state != RewriteState.Loading) {
        Text(
            text = "Ask for a version of your message that keeps the intent but avoids the problem above.",
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = { onRequestRewrite(null) }) {
            Text("Suggest a rewrite")
        }
    }

    if (state is RewriteState.Error) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = state.message, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
    }

    // The opening request replaces the panel with a spinner; a directed one
    // stays inside Success and spins only its own pill (handled below).
    if (state == RewriteState.Loading) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Rewriting...", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (success != null) {
        if (success.direction.isNotBlank()) {
            Card(
                shape = RoundedCornerShape(4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Text(
                    text = success.direction.uppercase(),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        Text(
            text = success.rewrittenSource,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = { onApplyRewrite(success.rewrittenSource) }) {
            Text("Use this version")
        }

        if (success.alternativeDirections.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Or try another angle:",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            success.alternativeDirections.forEach { alternative ->
                val inFlight = success.reloadingDirection == alternative
                Card(
                    onClick = { onRequestRewrite(alternative) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (inFlight) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(text = alternative, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * One titled block inside the details sheet, styled like the "Explanation"
 * section above it but with a leading icon so the added sections are
 * distinguishable at a glance while scrolling.
 */
@Composable
private fun DetailSection(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = body,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(32.dp))
}
