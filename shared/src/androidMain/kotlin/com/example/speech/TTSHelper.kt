package com.example.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TTSHelper(private val context: Context, private val onInitCompleted: (Boolean) -> Unit) {
    companion object {
        private const val TAG = "TTSHelper"
    }
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val pendingUtterances = java.util.concurrent.ConcurrentHashMap<String, UtteranceData>()

    private data class UtteranceData(val file: java.io.File, val targetDeviceId: Int)

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                Log.d(TAG, "TextToSpeech initialized successfully.")
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != null && pendingUtterances.containsKey(utteranceId)) {
                            val data = pendingUtterances.remove(utteranceId) ?: return
                            try {
                                val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                                val device = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).find { it.id == data.targetDeviceId }
                                
                                val mp = android.media.MediaPlayer()
                                val fis = java.io.FileInputStream(data.file)
                                mp.setDataSource(fis.fd)
                                fis.close()
                                mp.setAudioAttributes(
                                    android.media.AudioAttributes.Builder()
                                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .build()
                                )
                                if (device != null) {
                                    mp.setPreferredDevice(device)
                                }
                                mp.prepare()
                                mp.setOnCompletionListener { 
                                    it.release()
                                    data.file.delete()
                                }
                                mp.start()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error playing routed TTS", e)
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    android.widget.Toast.makeText(context, "TTS Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                }
                                data.file.delete()
                            }
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS Utterance Error")
                        if (utteranceId != null) {
                            pendingUtterances.remove(utteranceId)?.file?.delete()
                        }
                    }
                })
                onInitCompleted(true)
            } else {
                Log.e(TAG, "Initialization of TextToSpeech failed with status code: $status")
                onInitCompleted(false)
            }
        }
    }

    fun speak(text: String, locale: Locale, targetDeviceId: Int? = null) {
        if (!isInitialized) {
            Log.e(TAG, "Cannot speak. TTS is not initialized yet.")
            return
        }

        try {
            val result = tts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language is not supported or missing data for locale: $locale")
                tts?.setLanguage(Locale.US)
            }
            
            if (targetDeviceId != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val utteranceId = "RouteSpeechID_${System.currentTimeMillis()}"
                val file = java.io.File(context.externalCacheDir ?: context.cacheDir, "$utteranceId.wav")
                pendingUtterances[utteranceId] = UtteranceData(file, targetDeviceId)
                tts?.synthesizeToFile(text, null, file, utteranceId)
            } else {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TranslationSpeechID")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed playing TextToSpeech: ", e)
        }
    }

    fun stop() {
        if (isInitialized) {
            tts?.stop()
        }
    }

    fun release() {
        if (isInitialized) {
            tts?.stop()
            tts?.shutdown()
            isInitialized = false
        }
    }
}
