package com.example.wear

import com.example.model.Language

/**
 * Web [TranslationBroadcaster]: no Wear OS on the browser, so broadcasting a
 * translation is a no-op. `capabilities.wearSync` is false on web.
 */
object WebTranslationBroadcaster : TranslationBroadcaster {
    override fun broadcast(originalText: String, translatedText: String, sourceLang: Language, targetLang: Language) {
        /* no wearables on web */
    }
}
