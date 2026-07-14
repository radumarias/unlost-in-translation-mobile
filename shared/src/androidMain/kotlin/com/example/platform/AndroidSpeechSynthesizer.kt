package com.example.platform

import com.example.speech.TTSHelper
import java.util.Locale

/**
 * Android [SpeechSynthesizer] delegating to the existing [TTSHelper] (which keeps
 * the proven dual-headset routing via synthesizeToFile + MediaPlayer). Converts
 * the BCP 47 [languageTag] to a [Locale] at the boundary, so callers no longer
 * wrap `Locale.forLanguageTag(...)` themselves.
 */
class AndroidSpeechSynthesizer(
    private val ttsHelper: TTSHelper,
) : SpeechSynthesizer {
    override fun speak(text: String, languageTag: String, outputDeviceId: Int?) {
        ttsHelper.speak(text, Locale.forLanguageTag(languageTag), outputDeviceId)
    }

    override fun stop() = ttsHelper.stop()
    override fun release() = ttsHelper.release()
}
