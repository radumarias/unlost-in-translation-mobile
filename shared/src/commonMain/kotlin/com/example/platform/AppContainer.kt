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

    val log: AppLog
    val platformActions: PlatformActions
    val capabilities: PlatformCapabilities
}
