package com.example.platform

import com.example.AppSecrets
import com.example.data.LocalStorageHistoryRepository
import com.example.data.TranslationHistoryRepository
import com.example.link.ConversationLink
import com.example.link.OfflineConversationLink
import com.example.link.WebSocketConversationLink
import com.example.link.linkHttpClient
import com.example.network.KtorTranslationApi
import com.example.network.TranslationApi
import com.example.network.translationHttpClient
import com.example.wear.TranslationBroadcaster
import com.example.wear.WebTranslationBroadcaster
import io.ktor.client.engine.js.Js
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Web [AppContainer]. Mirror of the Android container but with browser-backed
 * services: localStorage history, Web Speech API TTS and speech recognition
 * (the latter feature-detected — Chromium only), and no-ops for the
 * Android-only capabilities (Wear sync, dual-headset).
 */
class WebAppContainer : AppContainer {

    private val voiceInputAvailable = speechRecognitionAvailable()
    private val imageInputAvailable = imageInputAvailable()

    override val log: AppLog = WebAppLog()

    override val api: TranslationApi = KtorTranslationApi(
        apiKey = AppSecrets.geminiApiKey,
        client = translationHttpClient(Js.create()),
        log = log,
    )

    override val historyRepository: TranslationHistoryRepository =
        LocalStorageHistoryRepository(log)

    override val speechSynthesizer: SpeechSynthesizer = WebSpeechSynthesizer()

    override val voiceInput: VoiceInput =
        if (voiceInputAvailable) WebVoiceInput(log) else UnavailableVoiceInput

    override val imagePicker: ImagePicker =
        if (imageInputAvailable) WebImagePicker(log) else UnavailableImagePicker

    override val audioOutputCatalog: AudioOutputCatalog = WebAudioOutputCatalog

    override val broadcaster: TranslationBroadcaster = WebTranslationBroadcaster

    /**
     * Blank when this build has no `RELAY_BASE_URL`; the capability below then turns
     * the feature off rather than offering a pairing screen that could only fail.
     *
     * Ktor's Js engine speaks WebSocket, the transport is `commonMain` and the
     * WebCrypto provider is already installed, so the browser runs the same client
     * the phone does. What the web build still cannot do is *scan* — see
     * [PlatformCapabilities.qrScan].
     */
    private val relayBaseUrl: String = AppSecrets.relayBaseUrl

    override val conversationLink: ConversationLink =
        if (relayBaseUrl.isBlank()) {
            OfflineConversationLink
        } else {
            WebSocketConversationLink(
                client = linkHttpClient(Js.create()),
                relayBaseUrl = relayBaseUrl,
                scope = CoroutineScope(SupervisorJob()),
                log = log,
            )
        }

    override val qrScanner: QrScanner = UnavailableQrScanner

    /**
     * The page's own origin, so a QR shown in the browser is a link back to the very
     * build that drew it — including `http://localhost:8080`, which is what makes
     * this testable without deploying anything.
     */
    override val pairingLinkBaseUrl: String = window.location.origin

    override val platformActions: PlatformActions = WebPlatformActions

    override val capabilities: PlatformCapabilities = PlatformCapabilities(
        voiceInput = voiceInputAvailable,
        dualHeadset = false,
        wearSync = false,
        imageInput = imageInputAvailable,
        // The wasm canvas ships no emoji font — flags/dingbats draw as boxes.
        extendedGlyphs = false,
        remoteConversation = relayBaseUrl.isNotBlank(),
        // No Shape Detection API on desktop Chromium, and none at all on Firefox or
        // Safari. A phone opening this build scans with its own camera app instead,
        // which is exactly what the deep link in the QR is for.
        qrScan = false,
    )
}

/** Web entry-point factory used by :webApp's main.kt. */
fun createAppContainer(): AppContainer = WebAppContainer()
