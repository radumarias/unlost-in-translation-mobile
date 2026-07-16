package com.example.platform

/**
 * Web [SpeechSynthesizer] over the browser Web Speech API
 * (`window.speechSynthesis`). [languageTag] (BCP 47) is passed straight through as
 * the utterance `lang`; the browser picks a matching voice. [outputDeviceId] is
 * ignored — the browser has no per-device audio routing (dual-headset is
 * Android-only). Browsers without the API (some webviews) degrade to a no-op
 * instead of throwing across the wasm boundary.
 */
class WebSpeechSynthesizer : SpeechSynthesizer {
    override fun speak(text: String, languageTag: String, outputDeviceId: Int?) {
        if (text.isBlank() || !speechSynthesisAvailable()) return
        speakViaBrowser(text, languageTag)
    }

    override fun stop() {
        if (speechSynthesisAvailable()) cancelSpeech()
    }

    override fun release() {
        // Nothing to release; the browser owns the speechSynthesis singleton.
        if (speechSynthesisAvailable()) cancelSpeech()
    }
}

private fun speechSynthesisAvailable(): Boolean =
    js("typeof window !== 'undefined' && 'speechSynthesis' in window")

private fun speakViaBrowser(text: String, lang: String) {
    js(
        """{
        var u = new SpeechSynthesisUtterance(text);
        u.lang = lang;
        window.speechSynthesis.cancel();
        window.speechSynthesis.speak(u);
    }"""
    )
}

private fun cancelSpeech() {
    js("window.speechSynthesis.cancel()")
}
