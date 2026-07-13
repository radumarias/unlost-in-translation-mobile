package com.example.wear

import android.content.Context
import com.example.model.Language

/**
 * Fire-and-forget sink for a successful primary translation (Phase 1 task 5).
 * Moving this behind an interface takes the platform side-effect out of the UI's
 * `LaunchedEffect` and into the view model (SRP; design §4). The Android impl
 * pushes to paired watches + posts a wearable notification; the web build
 * injects a no-op.
 */
interface TranslationBroadcaster {
    fun broadcast(originalText: String, translatedText: String, sourceLang: Language, targetLang: Language)
}

/** Android [TranslationBroadcaster]: delegates to [WearSyncManager] (Data + notification). */
class WearTranslationBroadcaster(
    private val context: Context,
) : TranslationBroadcaster {
    override fun broadcast(originalText: String, translatedText: String, sourceLang: Language, targetLang: Language) {
        WearSyncManager.syncTranslationToWear(context, originalText, translatedText, sourceLang, targetLang)
        WearSyncManager.sendWearableNotification(context, originalText, translatedText, sourceLang, targetLang)
    }
}
