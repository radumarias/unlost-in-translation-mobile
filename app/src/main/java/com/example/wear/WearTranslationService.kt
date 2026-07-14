package com.example.wear

import android.util.Log
import com.example.AppSecrets
import com.example.network.KtorTranslationApi
import com.example.network.TranslationApi
import com.example.network.translationHttpClient
import com.google.android.gms.wearable.MessageEvent
import io.ktor.client.engine.okhttp.OkHttp
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

class WearTranslationService : WearableListenerService() {
    companion object {
        private const val TAG = "WearTranslationService"
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Google-only path from the watch (design §9); the Gemini key is unused here.
    private val api: TranslationApi = KtorTranslationApi(
        apiKey = AppSecrets.geminiApiKey,
        client = translationHttpClient(OkHttp.create()),
    )

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        Log.d(TAG, "Received message event from Wear OS: Path='${messageEvent.path}'")

        if (messageEvent.path == "/translate_request") {
            val payload = messageEvent.data?.let { String(it) } ?: return
            serviceScope.launch {
                try {
                    // Expecting JSON with source_lang, target_lang, text
                    val json = JSONObject(payload)
                    val text = json.optString("text", "")
                    val sourceLang = json.optString("source_lang", "en")
                    val targetLang = json.optString("target_lang", "es")

                    Log.d(TAG, "Wear OS translation request: '$text' ($sourceLang -> $targetLang)")

                    if (text.isNotBlank()) {
                        // Deliver translation
                        val translated = api.translateGoogle(text, sourceLang, targetLang)
                        
                        // Send response back to the wear device
                        val messageClient = Wearable.getMessageClient(applicationContext)
                        val responseJson = JSONObject().apply {
                            put("original_text", text)
                            put("translated_text", translated)
                            put("status", "success")
                        }

                        messageClient.sendMessage(
                            messageEvent.sourceNodeId,
                            "/translate_response",
                            responseJson.toString().toByteArray()
                        )
                        Log.d(TAG, "Sent response back to Wear OS Node '${messageEvent.sourceNodeId}': $translated")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed processing Wear OS translation message request: ", e)
                    // Reply with error status
                    try {
                        val messageClient = Wearable.getMessageClient(applicationContext)
                        val errorJson = JSONObject().apply {
                            put("status", "error")
                            put("message", e.message ?: "Translation failed")
                        }
                        messageClient.sendMessage(
                            messageEvent.sourceNodeId,
                            "/translate_response",
                            errorJson.toString().toByteArray()
                        )
                    } catch (replyEx: Exception) {
                        Log.e(TAG, "Failed replying with error payload to Wear OS Node.", replyEx)
                    }
                }
            }
        }
    }
}
