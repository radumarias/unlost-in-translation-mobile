package com.example.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.util.Log

/**
 * Android [AudioOutputCatalog] over [AudioManager]. Enumerates the same routable
 * outputs the dual-headset dialog listed before (sinks that aren't the earpiece /
 * speaker / telephony / mic / FM / line), and plays a short notification tone to a
 * chosen device via MediaPlayer.setPreferredDevice — the test-sound logic lifted
 * verbatim from the old DeviceItemRow so the dialog can stay platform-free.
 */
class AndroidAudioOutputCatalog(
    private val context: Context,
) : AudioOutputCatalog {

    companion object {
        private const val TAG = "AudioOutputCatalog"
    }

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun listOutputDevices(): List<AudioOutputDevice> {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter {
                it.isSink &&
                    it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE &&
                    it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
                    it.type != AudioDeviceInfo.TYPE_TELEPHONY &&
                    it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC &&
                    it.type != AudioDeviceInfo.TYPE_FM &&
                    it.type != AudioDeviceInfo.TYPE_LINE_ANALOG &&
                    it.type != AudioDeviceInfo.TYPE_LINE_DIGITAL
            }
            .map { AudioOutputDevice(id = it.id, label = it.deviceLabel(), isBluetooth = it.isBluetooth()) }
    }

    override fun playTestTone(deviceId: Int) {
        try {
            val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).find { it.id == deviceId }
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val mediaPlayer = MediaPlayer()
            mediaPlayer.setDataSource(context, uri)
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && device != null) {
                mediaPlayer.setPreferredDevice(device)
            }
            mediaPlayer.prepare()
            mediaPlayer.setOnCompletionListener { it.release() }
            mediaPlayer.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play test sound: ${e.message}")
        }
    }
}

/** Human-readable label matching the old DeviceItemRow text: "name (type) [ID: n]". */
private fun AudioDeviceInfo.deviceLabel(): String {
    val typeName = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "SCO"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE"
        else -> "Type $type"
    }
    val name = productName.toString().ifBlank { "Unknown" }
    return "$name ($typeName) [ID: $id]"
}

private fun AudioDeviceInfo.isBluetooth(): Boolean =
    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        type == AudioDeviceInfo.TYPE_BLE_HEADSET
