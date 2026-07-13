package com.example.wear

import com.example.model.Language

/**
 * Fire-and-forget sink for a successful primary translation (design §3/§4).
 * Moving this behind an interface takes the platform side-effect out of the UI
 * and into the view model (SRP). The Android impl pushes to paired watches +
 * posts a wearable notification; the web build injects a no-op.
 */
interface TranslationBroadcaster {
    fun broadcast(originalText: String, translatedText: String, sourceLang: Language, targetLang: Language)
}
