package com.example.platform

import com.example.data.TranslationHistoryRepository
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
    val audioOutputCatalog: AudioOutputCatalog
    val broadcaster: TranslationBroadcaster
    val log: AppLog
    val platformActions: PlatformActions
    val capabilities: PlatformCapabilities
}
