package com.example.platform

import com.example.AppSecrets
import com.example.data.LocalStorageHistoryRepository
import com.example.data.TranslationHistoryRepository
import com.example.link.ConversationLink
import com.example.link.OfflineConversationLink
import com.example.network.KtorTranslationApi
import com.example.network.TranslationApi
import com.example.network.translationHttpClient
import com.example.wear.TranslationBroadcaster
import com.example.wear.WebTranslationBroadcaster
import io.ktor.client.engine.js.Js

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
     * Web guest-join is designed for and deferred, not dropped: it needs `:webApp`
     * deployed publicly, and the Gemini key is compiled into the wasm bundle and
     * extractable from it. That gate belongs to issue #1 and is not crossed.
     */
    override val conversationLink: ConversationLink = OfflineConversationLink

    override val qrScanner: QrScanner = UnavailableQrScanner

    override val platformActions: PlatformActions = WebPlatformActions

    override val capabilities: PlatformCapabilities = PlatformCapabilities(
        voiceInput = voiceInputAvailable,
        dualHeadset = false,
        wearSync = false,
        imageInput = imageInputAvailable,
        // The wasm canvas ships no emoji font — flags/dingbats draw as boxes.
        extendedGlyphs = false,
    )
}

/** Web entry-point factory used by :webApp's main.kt. */
fun createAppContainer(): AppContainer = WebAppContainer()
