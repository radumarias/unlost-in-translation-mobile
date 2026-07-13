package com.example

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.viewmodel.TranslateViewModel

@Composable
fun DualHeadsetSetupDialog(
    viewModel: TranslateViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    // Retrieve available audio devices (outputs that can conceptually represent headsets/earphones)
    var availableDevices by remember { mutableStateOf<List<AudioDeviceInfo>>(emptyList()) }
    
    LaunchedEffect(Unit) {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        availableDevices = devices.filter {
            it.isSink && 
            it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE &&
            it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
            it.type != AudioDeviceInfo.TYPE_TELEPHONY &&
            it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC &&
            it.type != AudioDeviceInfo.TYPE_FM &&
            it.type != AudioDeviceInfo.TYPE_LINE_ANALOG &&
            it.type != AudioDeviceInfo.TYPE_LINE_DIGITAL
        }
    }

    val dualHeadsetState by viewModel.dualHeadsetState.collectAsState()
    val sourceLang by viewModel.sourceLanguage.collectAsState()
    val targetLang by viewModel.targetLanguage.collectAsState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Dual Headphone Mode", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                Divider()

                Column(modifier = Modifier.padding(16.dp).weight(1f)) {
                    Text(
                        "Connect two sets of headphones. We will route each person's translations to their specific headphones.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = dualHeadsetState.isDualHeadsetModeEnabled,
                            onCheckedChange = { viewModel.setDualHeadsetModeEnabled(it) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Enable Dual Headset Routing", fontWeight = FontWeight.Medium)
                    }

                    if (dualHeadsetState.isDualHeadsetModeEnabled) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("Available Devices", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        if (availableDevices.isEmpty()) {
                            Text("No Bluetooth or Wired headsets found.", color = MaterialTheme.colorScheme.error)
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(availableDevices) { device ->
                                    DeviceItemRow(device, dualHeadsetState, viewModel, sourceLang.name, targetLang.name)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItemRow(
    device: AudioDeviceInfo,
    state: com.example.ui.viewmodel.DualHeadsetModel,
    viewModel: TranslateViewModel,
    sourceLangName: String,
    targetLangName: String
) {
    val isAssignedToA = state.deviceA == device.id
    val isAssignedToB = state.deviceB == device.id
    val context = LocalContext.current

    fun playTestSound(testIsA: Boolean, testIsB: Boolean) {
        try {
            val languageName = if (testIsA) sourceLangName else targetLangName
            val toastMsg = "Playing test sound to ${device.productName} in $languageName..."
            android.widget.Toast.makeText(context, toastMsg, android.widget.Toast.LENGTH_SHORT).show()
            
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            val mediaPlayer = android.media.MediaPlayer()
            mediaPlayer.setDataSource(context, uri)
            mediaPlayer.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                mediaPlayer.setPreferredDevice(device)
            }
            mediaPlayer.prepare()
            mediaPlayer.setOnCompletionListener { it.release() }
            mediaPlayer.start()
        } catch (e: Exception) {
            android.util.Log.e("DualHeadset", "Failed to play test sound: ${e.message}")
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isAssignedToA || isAssignedToB) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Headphones, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                val typeName = when(device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "A2DP"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "SCO"
                    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
                    AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE"
                    else -> "Type ${device.type}"
                }
                Text("${device.productName.toString().ifBlank { "Unknown" }} ($typeName) [ID: ${device.id}]", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Button to Assign to A
                ElevatedFilterChip(
                    selected = isAssignedToA,
                    onClick = { 
                        if (isAssignedToA) viewModel.setHeadsetForA(null) 
                        else {
                            if (isAssignedToB) viewModel.setHeadsetForB(null)
                            viewModel.setHeadsetForA(device.id)
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ playTestSound(true, false) }, 300)
                        }
                    },
                    label = { Text("Assign User 1 ($sourceLangName)") },
                    leadingIcon = if (isAssignedToA) { { Icon(Icons.Default.CheckCircle, contentDescription=null, modifier = Modifier.size(16.dp)) } } else null
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Button to Assign to B
                ElevatedFilterChip(
                    selected = isAssignedToB,
                    onClick = { 
                        if (isAssignedToB) viewModel.setHeadsetForB(null) 
                        else {
                            if (isAssignedToA) viewModel.setHeadsetForA(null)
                            viewModel.setHeadsetForB(device.id)
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ playTestSound(false, true) }, 300)
                        }
                    },
                    label = { Text("Assign User 2 ($targetLangName)") },
                    leadingIcon = if (isAssignedToB) { { Icon(Icons.Default.CheckCircle, contentDescription=null, modifier = Modifier.size(16.dp)) } } else null
                )
            }
            if (isAssignedToA || isAssignedToB) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { playTestSound(isAssignedToA, isAssignedToB) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Test Headphone Output")
                }
            }
        }
    }
}
