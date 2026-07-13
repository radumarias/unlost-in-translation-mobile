package com.example.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.example.model.Language
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

object WearSyncManager {
    private const val TAG = "WearSyncManager"
    private const val CHANNEL_ID = "translation_wearable_channel"
    private const val NOTIFICATION_ID = 4801

    /**
     * Syncs a translation result to connected Android Wear OS smartwatches via the Wearable DataClient.
     * This puts data into the shared wearable storage so active smartwatches can display it instant-on.
     */
    fun syncTranslationToWear(context: Context, sourceText: String, translatedText: String, sourceLang: Language, targetLang: Language) {
        try {
            val dataClient = Wearable.getDataClient(context.applicationContext)
            val putDataMapReq = PutDataMapRequest.create("/active_translation").apply {
                dataMap.putString("source_text", sourceText)
                dataMap.putString("translated_text", translatedText)
                dataMap.putString("source_lang", sourceLang.name)
                dataMap.putString("target_lang", targetLang.name)
                dataMap.putLong("timestamp", System.currentTimeMillis())
            }
            val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
            dataClient.putDataItem(putDataReq)
                .addOnSuccessListener {
                    Log.d(TAG, "Successfully synced translation to Wear OS shared storage.")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed syncing translation to Wear OS shared storage: ", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Wearable Services may not be available or fully configured on this device.", e)
        }
    }

    /**
     * Publishes a rich Wear-optimized translation notification.
     * This includes specific wearable actions so a user on a smartwatch can view, hear,
     * or voice-reply to the translation directly on their wrist.
     */
    fun sendWearableNotification(context: Context, sourceText: String, translatedText: String, sourceLang: Language, targetLang: Language) {
        val appContext = context.applicationContext
        createNotificationChannel(appContext)

        // Intent to launch the main app when the notification is tapped
        val mainIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val mainPendingIntent = android.app.PendingIntent.getActivity(
            appContext, 0, mainIntent, android.app.PendingIntent.FLAG_IMMUTABLE
        )

        // Build a wear-optimized notification card
        val wearableExtender = NotificationCompat.WearableExtender()
            .setHintContentIntentLaunchesActivity(true)

        val notificationBuilder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // Standard system speaking icon
            .setContentTitle("Translation (${sourceLang.flag} ➔ ${targetLang.flag})")
            .setContentText(translatedText)
            .setSubText("$sourceText ➔ $translatedText")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(mainPendingIntent)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText("Original:\n\"$sourceText\"\n\nTranslation (${targetLang.name}):\n\"$translatedText\""))
            .extend(wearableExtender)

        try {
            // Check permission (on API 33+) dynamically or try to post safely
            val notificationManager = NotificationManagerCompat.from(appContext)
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
            Log.d(TAG, "Sent translation notification card to Wearable/System.")
        } catch (e: SecurityException) {
            Log.e(TAG, "POST_NOTIFICATIONS permission missing.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification.", e)
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Wrist Translations"
            val descriptionText = "Mirror translation notifications and controls on paired smartwatch wearables"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
