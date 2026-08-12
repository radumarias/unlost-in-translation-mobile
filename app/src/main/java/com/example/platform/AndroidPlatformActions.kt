package com.example.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/**
 * Android [PlatformActions]: clipboard via [ClipboardManager] and share via an
 * ACTION_SEND chooser. Verbatim from the copy/share IconButtons that lived in
 * TranslateScreen before it moved to commonMain. The chooser Intent is started
 * with FLAG_ACTIVITY_NEW_TASK because [appContext] is the application context,
 * not an Activity.
 */
class AndroidPlatformActions(context: Context) : PlatformActions {

    private val appContext = context.applicationContext

    override fun copyToClipboard(text: String) {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("translation", text))
    }

    override fun share(text: String, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(intent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(chooser)
    }
}
