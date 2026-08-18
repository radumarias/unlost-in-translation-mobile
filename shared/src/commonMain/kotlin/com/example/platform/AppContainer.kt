package com.example.platform

import com.example.data.TranslationHistoryRepository
import com.example.link.ConversationLink
import com.example.network.TranslationApi
import com.example.wear.TranslationBroadcaster

/**
 * Hand-built dependency container (Phase 1 task 6, design §3). The UI and view
 * model resolve every platform service through this interface instead of touching
 * Android APIs directly, so a web build swaps in browser-backed implementations
 * by providing a different [AppContainer]. No DI framework (KISS).
 */
interface AppContainer {
    val api: TranslationApi
    val historyRepository: TranslationHistoryRepository
    val speechSynthesizer: SpeechSynthesizer
    val voiceInput: VoiceInput
    val imagePicker: ImagePicker
    val audioOutputCatalog: AudioOutputCatalog
    val broadcaster: TranslationBroadcaster

    /** Two-device conversation transport (issue #33); `OfflineConversationLink` where unsupported. */
    val conversationLink: ConversationLink

    /** Pairing-QR reader; `UnavailableQrScanner` where the entry point owns the real one. */
    val qrScanner: QrScanner

    /**
     * Origin the pairing QR should point at, e.g. `https://translate.example.com`, or
     * blank to keep the QR in its compact `uitr:` form.
     *
     * The web build knows its own origin and answers with it, so a QR shown by a
     * browser is always a link back to the same build — localhost included, which is
     * what makes the whole flow testable without deploying anything. Android has to
     * be told (`WEB_BASE_URL`), because nothing in an APK knows where the web build
     * lives.
     */
    val pairingLinkBaseUrl: String

    val log: AppLog
    val platformActions: PlatformActions
    val capabilities: PlatformCapabilities
}
