package com.example.wear

import android.content.Context
import com.example.model.Language

/**
 * Android [TranslationBroadcaster]: delegates to [WearSyncManager] (Data +
 * notification). Stays in :app for Phase 2 because [WearSyncManager] references
 * the app's MainActivity + R; it moves to :shared behind a DI abstraction in
 * Phase 3.
 */
class WearTranslationBroadcaster(
    private val context: Context,
) : TranslationBroadcaster {
    override fun broadcast(originalText: String, translatedText: String, sourceLang: Language, targetLang: Language) {
        WearSyncManager.syncTranslationToWear(context, originalText, translatedText, sourceLang, targetLang)
        WearSyncManager.sendWearableNotification(context, originalText, translatedText, sourceLang, targetLang)
    }
}
