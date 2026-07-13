package com.example.platform

/**
 * Platform-service seams the UI / view model depend on instead of Android APIs
 * (Phase 1 task 6, design §3). Android impls live alongside; a web build supplies
 * browser-backed / no-op versions later.
 */

/** Text-to-speech; absorbs TTSHelper's public surface. [languageTag] is BCP 47. */
interface SpeechSynthesizer {
    fun speak(text: String, languageTag: String, outputDeviceId: Int? = null)
    fun stop()
    fun release()
}

/** Speech recognition. Returns the recognised text, or null if cancelled/unavailable. */
interface VoiceInput {
    suspend fun recognize(languageTag: String, prompt: String): String?
}

/**
 * [VoiceInput] that always reports "no result". Used where a real recognizer is
 * not (yet) bound — e.g. the Android container in Phase 1, where MainActivity's
 * own activity-result launcher is still the live voice path.
 */
object UnavailableVoiceInput : VoiceInput {
    override suspend fun recognize(languageTag: String, prompt: String): String? = null
}

/** An addressable audio output (headset/earbud) for dual-headset routing. */
data class AudioOutputDevice(val id: Int, val label: String, val isBluetooth: Boolean)

/**
 * Enumerates routable audio outputs and can play a short test tone to one, so the
 * dual-headset dialog needs no Android audio APIs of its own (web: [listOutputDevices]
 * returns emptyList and [playTestTone] is a no-op).
 */
interface AudioOutputCatalog {
    fun listOutputDevices(): List<AudioOutputDevice>
    fun playTestTone(deviceId: Int)
}

/** Feature flags that gate capability-specific UI (design §3). */
data class PlatformCapabilities(
    val voiceInput: Boolean,
    val dualHeadset: Boolean,
    val wearSync: Boolean,
)
