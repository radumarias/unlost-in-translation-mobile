package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import com.example.link.LinkState
import com.example.link.SessionTicket
import com.example.ui.i18n.LocalStrings
import com.example.ui.i18n.withArg
import com.example.ui.screens.peerLanguageLabel
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

/**
 * Pairs this phone with the one the other person is holding (issue #33 Phase 5).
 *
 * Both halves are in one dialog because pairing is one decision with two sides:
 * whoever opens it first shows a code, whoever opens it second reads one. The
 * dialog does not decide which — [ticket] being non-null is what makes this the
 * hosting side. A guest has no ticket of its own, so [isRemote] is what tells the
 * two "no ticket" cases apart: not paired yet, versus paired as the guest.
 *
 * The QR is drawn from the ticket's compact form rather than its URL form: the URL
 * exists for the deferred web-guest path and is roughly twice as long, which pushes
 * the symbol into a denser version for nothing when both ends are this app.
 */
@Composable
fun PairingDialog(
    ticket: SessionTicket?,
    linkState: LinkState,
    canScan: Boolean,
    isRemote: Boolean,
    onStartHosting: () -> Unit,
    onStartHostingWithCode: () -> Unit,
    onJoin: (String) -> Boolean,
    onScan: () -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    var typed by remember { mutableStateOf("") }
    var rejected by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.pairingTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = strings.pairingBlurb,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                if (ticket != null) {
                    HostingSide(ticket, linkState)
                } else if (isRemote) {
                    // A guest publishes no ticket, so there is nothing to show it —
                    // but it is in a session, and offering it the join form again
                    // (with a button that re-hosts) is how someone ends up leaving
                    // by turning conversation mode off and losing the history.
                    ConnectedSide(linkState)
                } else {
                    JoiningSide(
                        typed = typed,
                        rejected = rejected,
                        canScan = canScan,
                        onTypedChange = {
                            typed = it
                            rejected = false
                        },
                        onScan = onScan,
                        onJoin = { rejected = !onJoin(typed) },
                        onStartHosting = onStartHosting,
                        onStartHostingWithCode = onStartHostingWithCode,
                    )
                }
            }
        },
        confirmButton = {
            if (ticket != null || isRemote) {
                TextButton(onClick = onLeave) { Text(strings.pairingLeave) }
            } else {
                TextButton(onClick = onDismiss) { Text(strings.cancel) }
            }
        },
    )
}

/**
 * The five-digit short authentication string, on the manual-code path only
 * (issue #34 §5).
 *
 * A typed code carries about 30 bits, so it cannot authenticate the other phone's
 * key the way a scanned QR does. The two people are standing next to each other by
 * definition of the product — "does yours say 41 908?" is free UX here in a way it
 * never is in a real messenger — and it is the only thing standing between this path
 * and a relay in the middle.
 *
 * Digits, never words or emoji: the wasm canvas has no emoji font, and digits read
 * aloud across a table in any of the sixteen languages this app supports.
 */
@Composable
fun SasCheckDialog(
    sas: String,
    onMatches: () -> Unit,
    onDoesNotMatch: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        // No dismiss: nothing is rendered until this is answered, so a stray tap
        // outside must not leave the session in limbo.
        onDismissRequest = {},
        title = { Text(strings.sasTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    // Grouped 2-3 the way a person reads a number out loud.
                    text = sas.take(2) + " " + sas.drop(2),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = strings.sasBlurb,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onMatches) { Text(strings.sasMatches) } },
        dismissButton = {
            TextButton(onClick = onDoesNotMatch) {
                Text(strings.sasDoesNotMatch, color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun HostingSide(ticket: SessionTicket, linkState: LinkState) {
    val strings = LocalStrings.current
    val code = ticket.manualCode
    if (code == null) {
        Text(strings.pairingShowThisCode, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        // A white quiet zone around the symbol: on a dark theme the surface behind
        // it is dark, and a QR drawn straight onto that does not scan.
        Box(
            modifier = Modifier
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Image(
                painter = rememberQrCodePainter(ticket.encodeCompact()),
                contentDescription = strings.pairingScanQr,
                modifier = Modifier.size(200.dp),
            )
        }
    } else {
        Text(strings.pairingCodeIs, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            text = code,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            // Wide letter spacing: this is read out loud across a table, and the
            // gaps are what stop "8" and "B" running together.
            letterSpacing = 6.sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    Spacer(Modifier.height(16.dp))
    PairingStatus(linkState)
}

/**
 * What a guest sees when it reopens the dialog mid-session: the same status line the
 * host gets, and the Leave button beside it.
 */
@Composable
private fun ConnectedSide(linkState: LinkState) {
    Text(
        text = LocalStrings.current.linkThisPhone,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    PairingStatus(linkState)
}

@Composable
private fun PairingStatus(linkState: LinkState) {
    val strings = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (linkState is LinkState.Connected && linkState.peerPresent) {
            Text(
                // The language *name*, matching the banner under the header — the
                // same peer described two different ways in two adjacent surfaces
                // reads as two different peers.
                text = strings.linkConnectedWith.withArg(peerLanguageLabel(linkState.peerLang)),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(strings.pairingWaiting, fontSize = 13.sp)
        }
    }
}

@Composable
private fun JoiningSide(
    typed: String,
    rejected: Boolean,
    canScan: Boolean,
    onTypedChange: (String) -> Unit,
    onScan: () -> Unit,
    onJoin: () -> Unit,
    onStartHosting: () -> Unit,
    onStartHostingWithCode: () -> Unit,
) {
    val strings = LocalStrings.current

    if (canScan) {
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(strings.pairingScanQr)
        }
        Spacer(Modifier.height(12.dp))
        // Names the fallback rather than leaving a bare field under the scan button:
        // the typed code is for a camera that will not focus, a cracked lens, or a
        // phone held by someone who cannot aim it.
        Text(
            text = strings.pairingCantScan,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }

    OutlinedTextField(
        value = typed,
        onValueChange = onTypedChange,
        label = { Text(strings.pairingEnterCode) },
        placeholder = { Text(strings.pairingCodePlaceholder) },
        singleLine = true,
        isError = rejected,
        supportingText = if (rejected) {
            { Text(strings.pairingNotACode, color = MaterialTheme.colorScheme.error) }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = onJoin,
        enabled = typed.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(strings.pairingJoin) }

    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = onStartHosting) { Text(strings.pairingShowThisCode) }
    }
    // The typed-code path is offered second and named honestly: it is the weaker
    // one, and issue #34 makes the difference visible to the user rather than
    // leaving it as an implementation detail.
    TextButton(onClick = onStartHostingWithCode) { Text(strings.pairingTypeCodeInstead) }
}
