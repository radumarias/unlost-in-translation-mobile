package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhonelinkRing
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.DualHeadsetEntry
import com.example.model.Language
import com.example.model.TurnOrigin
import com.example.model.flagLabel
import com.example.platform.AudioOutputCatalog
import com.example.platform.SpeechSynthesizer
import com.example.link.LinkFailure
import com.example.link.LinkState
import com.example.link.Verification
import com.example.ui.components.ConversationDraftDialog
import com.example.ui.components.PairingDialog
import com.example.ui.components.SasCheckDialog
import com.example.ui.components.ToneSituationBar
import com.example.ui.i18n.LocalStrings
import com.example.ui.i18n.Strings
import com.example.ui.i18n.withArg
import com.example.ui.viewmodel.TranslateViewModel

/**
 * Conversation split-screen: bilingual message flow plus the two-party mic deck,
 * the dual-headset setup entry point, and the draft-confirmation dialog.
 *
 * In commonMain (design §1). TTS goes through [speechSynthesizer] (BCP-47 tag,
 * no java.util.Locale) and status toasts through `viewModel.showMessage`; the
 * dual-headset device selection stays behind the [audioOutputCatalog] seam.
 */
@Composable
fun ConversationScreen(
    viewModel: TranslateViewModel,
    sourceLang: Language,
    targetLang: Language,
    speechSynthesizer: SpeechSynthesizer,
    audioOutputCatalog: AudioOutputCatalog,
    showVoiceInput: Boolean,
    showDualHeadset: Boolean,
    extendedGlyphs: Boolean,
    launchVoiceInput: (isConversationA: Boolean?, language: Language) -> Unit,
    onShowFullScreen: (String) -> Unit = {},
    /** Issue #33: false hides pairing entirely (web, or a build with no relay). */
    showRemoteConversation: Boolean = false,
    /** Opens the platform QR scanner; the payload comes back through the view model. */
    launchQrScan: () -> Unit = {},
) {
    val strings = LocalStrings.current
    val conversationHistory by viewModel.conversationHistory.collectAsState()
    val tone by viewModel.tone.collectAsState()
    val situation by viewModel.situation.collectAsState()
    val isRemote by viewModel.isRemoteSession.collectAsState()
    val remoteTicket by viewModel.remoteTicket.collectAsState()
    val linkState by viewModel.linkState.collectAsState()
    // Read here rather than next to the SAS dialog below: the banner needs it too,
    // and the banner is rendered first.
    val sasConfirmed by viewModel.sasConfirmed.collectAsState()
    var showPairing by remember { mutableStateOf(false) }

    // A turn from the paired phone arrives as text and nothing else. A local turn is
    // spoken by the draft-confirmation callback further down; without the same here,
    // the peer's translation lands silently and the user has to notice a new bubble
    // and press play — which is not a conversation.
    //
    // Seeded with whatever is already on screen so re-entering the tab does not
    // replay the whole exchange, and the ids that have been spoken are kept rather
    // than the last one alone, because the SAS gate releases several turns at once.
    val spokenRemoteTurns = remember {
        conversationHistory.filter { it.origin == TurnOrigin.REMOTE }.map { it.id }.toMutableSet()
    }
    LaunchedEffect(conversationHistory) {
        conversationHistory
            .filter { it.origin == TurnOrigin.REMOTE && spokenRemoteTurns.add(it.id) }
            .forEach { item ->
                // No headset device: remote mode and dual-headset mode are mutually
                // exclusive, so there is never an assignment to honour here.
                speechSynthesizer.speak(
                    item.translated,
                    Language.getByCode(item.tgtLangCode).languageTag,
                    null,
                )
            }
    }

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
                Icon(Icons.Default.ArrowBack, contentDescription = strings.back)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = strings.conversationMode,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.weight(1f))
            if (showRemoteConversation) {
                // Pairing lives in the conversation header rather than the app's
                // main chrome: it is a property of *this* conversation, and putting
                // it here keeps the translate tab untouched.
                IconButton(onClick = { showPairing = true }) {
                    Icon(
                        Icons.Default.PhonelinkRing,
                        contentDescription = strings.pairDevices,
                        tint = if (isRemote) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            DualHeadsetEntry(
                viewModel = viewModel,
                catalog = audioOutputCatalog,
                show = showDualHeadset && !isRemote,
            )
        }

        // A banner, not a snackbar: `messages` is a no-replay SharedFlow, so a
        // state change emitted before the collector attaches would be lost, and
        // "are we still connected?" is a question about now rather than an event.
        if (isRemote) LinkBanner(linkState, sasConfirmed)

        // Tone / situation constraints — they shape every turn of the conversation
        ToneSituationBar(
            tone = tone,
            situation = situation,
            extendedGlyphs = extendedGlyphs,
            onSelectTone = { viewModel.setTone(it) },
            onSelectSituation = { viewModel.setSituation(it) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Showing the bubble to the other person is the
                                // whole point of conversation mode, so the
                                // flashcard belongs on every turn.
                                IconButton(
                                    onClick = { onShowFullScreen(item.translated) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Fullscreen,
                                        contentDescription = strings.showFullScreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        val l = if (item.speakA) targetLang else sourceLang
                                        val d = if (viewModel.dualHeadsetState.value.isDualHeadsetModeEnabled) {
                                            if (item.speakA) viewModel.dualHeadsetState.value.deviceB else viewModel.dualHeadsetState.value.deviceA
                                        } else null
                                        speechSynthesizer.speak(item.translated, l.languageTag, d)
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.VolumeUp,
                                        contentDescription = strings.speakTranslation,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
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
                // One SpeakerInput per party: mic FAB when voice input is available,
                // typed input field otherwise (web MVP: plan §7 Phase 3 acceptance —
                // "conversation mode works via typed input"). Both feed the same
                // prepareConversationPart → draft-confirmation flow.
                SpeakerInput(
                    language = sourceLang,
                    isLanguageA = true,
                    showVoiceInput = showVoiceInput,
                    extendedGlyphs = extendedGlyphs,
                    onVoiceClick = { launchVoiceInput(true, sourceLang) },
                    onSubmit = { viewModel.prepareConversationPart(it, isLanguageA = true) }
                )

                // With two phones the other person speaks into their own device, so
                // the second mic would be a button nobody can honestly press. It
                // becomes a status strip naming the language coming back.
                if (isRemote) {
                    PeerStrip(language = targetLang, extendedGlyphs = extendedGlyphs)
                } else {
                    SpeakerInput(
                        language = targetLang,
                        isLanguageA = false,
                        showVoiceInput = showVoiceInput,
                        extendedGlyphs = extendedGlyphs,
                        onVoiceClick = { launchVoiceInput(false, targetLang) },
                        onSubmit = { viewModel.prepareConversationPart(it, isLanguageA = false) }
                    )
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
                        val userStr = if (isA) strings.user2 else strings.user1
                        viewModel.showMessage(strings.playingTranslationToHeadset.withArg(userStr))
                    }
                    val targetLanguageTag = if (isA) targetLang.languageTag else sourceLang.languageTag
                    val targetDevice = if (dualState.isDualHeadsetModeEnabled) {
                        if (isA) dualState.deviceB else dualState.deviceA
                    } else null
                    speechSynthesizer.speak(translated, targetLanguageTag, targetDevice)
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

    // Issue #34 §5: on the typed-code path nothing is rendered or spoken until both
    // people confirm the digits match. This sits above the pairing dialog on purpose
    // — it is the last thing between an unverified peer and the screen.
    val pendingSas = (linkState as? LinkState.Connected)
        ?.takeIf { it.verification == Verification.NEEDS_SAS_CHECK && !sasConfirmed }
        ?.sas
    if (isRemote && pendingSas != null) {
        SasCheckDialog(
            sas = pendingSas,
            onMatches = { viewModel.confirmSasMatch() },
            onDoesNotMatch = { viewModel.rejectSasMatch() },
        )
    }

    if (showPairing) {
        PairingDialog(
            ticket = remoteTicket,
            linkState = linkState,
            canScan = showRemoteConversation,
            isRemote = isRemote,
            onStartHosting = { viewModel.startRemoteSession() },
            onStartHostingWithCode = { viewModel.startRemoteSessionWithCode() },
            onJoin = { payload ->
                val joined = viewModel.joinRemoteSession(payload)
                if (joined) showPairing = false
                joined
            },
            onScan = {
                showPairing = false
                launchQrScan()
            },
            onLeave = {
                viewModel.endRemoteSession()
                showPairing = false
            },
            onDismiss = { showPairing = false },
        )
    }
}

/**
 * What the banner says for a given [state], and whether it reads as a problem —
 * null when there is nothing to show.
 *
 * A plain function rather than logic inside the composable, so a test can walk
 * every [LinkFailure] and prove none of them renders an empty banner. Kotlin's
 * exhaustive `when` catches a *new* reason at compile time; only a test catches
 * one that compiles because it was wired to a blank string.
 */
/**
 * The peer's language as a person would say it, not as the wire carries it.
 *
 * Shared with the pairing dialog so the same peer is not described as "Romanian"
 * under the header and "ro" inside the dialog above it. An unknown tag falls back
 * to the tag: a peer on a language this build does not list is still worth naming.
 */
internal fun peerLanguageLabel(code: String?): String =
    Language.findByCode(code.orEmpty())?.name ?: code.orEmpty()

/**
 * The strip under the header, as `text to isProblem`, or null when there is nothing
 * to say.
 *
 * [sasConfirmed] is the view model's, not the link's: confirming the digits is a
 * decision the *user* makes about a session whose [Verification] never changes —
 * the link cannot know whether two people compared five digits out loud. Without it
 * the banner goes on telling them to check a code they have already checked, and
 * the verified state has no way to be announced at all.
 */
internal fun linkBannerText(
    state: LinkState,
    strings: Strings,
    sasConfirmed: Boolean = false,
): Pair<String, Boolean>? = when (state) {
    is LinkState.Idle -> null
    is LinkState.Connecting -> strings.linkConnecting to false
    // The handshake is fast, but naming it is worth a string: if it stalls, the
    // user is looking at a screen that would otherwise say nothing at all.
    is LinkState.KeyExchange, is LinkState.Confirming -> strings.linkSecuring to false
    is LinkState.Connected ->
        if (state.peerPresent) {
            val who = strings.linkConnectedWith.withArg(peerLanguageLabel(state.peerLang))
            // The two verification states are named apart, honestly: the QR
            // authenticated the other phone's key, a typed code cannot.
            val trust = when (state.verification) {
                Verification.VERIFIED_BY_QR -> strings.linkVerifiedByQr
                // Named apart from the QR case for the same reason the two
                // verification states are: comparing digits out loud is a weaker
                // claim than a scanned key, and the banner should not pretend
                // otherwise once the check is done.
                Verification.NEEDS_SAS_CHECK ->
                    if (sasConfirmed) strings.linkVerifiedBySas else strings.linkNeedsSasCheck
                Verification.NONE -> null
            }
            // Not a middle dot: the wasm canvas cannot draw one, which is the same
            // constraint `StringsTest` enforces on every locale.
            (if (trust == null) who else "$who — $trust") to false
        } else {
            strings.pairingWaiting to false
        }
    is LinkState.Failed -> when (state.reason) {
        LinkFailure.PEER_LEFT -> strings.linkPeerLeft to true
        // Issue #34 §3.7: there is no plaintext fallback, so this is terminal and
        // the user is told rather than quietly downgraded.
        LinkFailure.ENCRYPTION_FAILED -> strings.linkFailedEncryption to true
        LinkFailure.NOT_CONFIGURED -> strings.linkFailedUnconfigured to true
        LinkFailure.PROTOCOL -> strings.linkFailedProtocol to true
        // "Session full" arrives as a failed WebSocket upgrade, and neither the
        // OkHttp engine nor a browser can read the HTTP status of one, so it is
        // reported as what the user actually experiences: no connection.
        LinkFailure.SESSION_FULL,
        LinkFailure.SESSION_EXPIRED,
        LinkFailure.NETWORK -> strings.linkFailedNetwork to true
    }
}

/** The connection state of the paired device, as a strip under the header. */
@Composable
private fun LinkBanner(state: LinkState, sasConfirmed: Boolean) {
    val (message, isProblem) = linkBannerText(state, LocalStrings.current, sasConfirmed) ?: return
    Surface(
        color = if (isProblem) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = if (isProblem) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
        )
    }
}

/** The peer's side of the deck in a two-phone session: a label, not an input. */
@Composable
private fun RowScope.PeerStrip(language: Language, extendedGlyphs: Boolean) {
    val strings = LocalStrings.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
    ) {
        Text(
            text = language.name + " " + language.flagLabel(extendedGlyphs),
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = strings.linkOtherPhone,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One party's input slot on the conversation deck: language label above either
 * the mic FAB (voice input available) or a typed-input field (web MVP). Both
 * feed the same prepareConversationPart → draft-confirmation flow.
 */
@Composable
private fun RowScope.SpeakerInput(
    language: Language,
    isLanguageA: Boolean,
    showVoiceInput: Boolean,
    extendedGlyphs: Boolean,
    onVoiceClick: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val strings = LocalStrings.current
    val party = if (isLanguageA) "a" else "b"
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (showVoiceInput) Modifier else Modifier.weight(1f).padding(horizontal = 8.dp)
    ) {
        Text(
            text = language.name + " " + language.flagLabel(extendedGlyphs),
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (showVoiceInput) {
            FloatingActionButton(
                onClick = onVoiceClick,
                containerColor = if (isLanguageA) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.testTag("mic_party_$party")
            ) {
                Icon(Icons.Default.Mic, contentDescription = if (isLanguageA) strings.micA else strings.micB)
            }
        } else {
            ConversationTypedInput(
                languageName = language.name,
                testTag = "typed_input_party_$party",
                onSubmit = onSubmit
            )
        }
    }
}

/**
 * Typed replacement for a speaker's mic FAB when voice input is unavailable
 * (web MVP). Submitting hands the text to the same draft-confirmation pipeline
 * that voice recognition feeds.
 */
@Composable
private fun ConversationTypedInput(
    languageName: String,
    testTag: String,
    onSubmit: (String) -> Unit,
) {
    val strings = LocalStrings.current
    var text by remember { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onSubmit(text.trim())
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(strings.typeInLanguage.withArg(languageName), fontSize = 14.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { submit() }),
        trailingIcon = {
            IconButton(
                onClick = submit,
                enabled = text.isNotBlank()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = strings.sendLanguageMessage.withArg(languageName))
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    )
}
