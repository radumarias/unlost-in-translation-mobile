package com.example.wear

import android.content.Context
import com.example.model.Language

/**
 * Android [TranslationBroadcaster]: delegates to [WearSyncManager] (Data +
 * notification). Moves to shared/androidMain in task 6.
 */
class WearTranslationBroadcaster(
    private val context: Context,
) : TranslationBroadcaster {
    override fun broadcast(originalText: String, translatedText: String, sourceLang: Language, targetLang: Language) {
        WearSyncManager.syncTranslationToWear(context, originalText, translatedText, sourceLang, targetLang)
        WearSyncManager.sendWearableNotification(context, originalText, translatedText, sourceLang, targetLang)
    }
}
