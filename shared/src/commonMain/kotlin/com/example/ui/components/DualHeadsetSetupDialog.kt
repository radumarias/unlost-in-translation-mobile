package com.example.ui.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.platform.AudioOutputCatalog
import com.example.platform.AudioOutputDevice
import com.example.ui.viewmodel.DualHeadsetModel
import com.example.ui.viewmodel.TranslateViewModel

/**
 * Dual-headset setup. Rewritten against [AudioOutputCatalog] (Phase 1 task 6): it
 * no longer touches AudioManager / MediaPlayer directly — device enumeration and
 * the test tone come from the catalog, and the "playing test sound" message goes
 * through the view model's snackbar stream. The layout is unchanged.
 */
@Composable
fun DualHeadsetSetupDialog(
    viewModel: TranslateViewModel,
    catalog: AudioOutputCatalog,
    onDismiss: () -> Unit
) {
    // Retrieve available audio outputs (headsets/earphones) from the platform catalog
    var availableDevices by remember { mutableStateOf<List<AudioOutputDevice>>(emptyList()) }

    LaunchedEffect(Unit) {
        availableDevices = catalog.listOutputDevices()
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
                                    DeviceItemRow(device, dualHeadsetState, viewModel, catalog, sourceLang.name, targetLang.name)
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
    device: AudioOutputDevice,
    state: DualHeadsetModel,
    viewModel: TranslateViewModel,
    catalog: AudioOutputCatalog,
    sourceLangName: String,
    targetLangName: String
) {
    val isAssignedToA = state.deviceA == device.id
    val isAssignedToB = state.deviceB == device.id

    fun playTestSound(testIsA: Boolean) {
        val languageName = if (testIsA) sourceLangName else targetLangName
        viewModel.showMessage("Playing test sound to ${device.label} in $languageName...")
        catalog.playTestTone(device.id)
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
                Text(device.label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
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
                            playTestSound(true)
                        }
                    },
                    label = { Text("Assign User 1 ($sourceLangName)") },
                    leadingIcon = if (isAssignedToA) { { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) } } else null
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
                            playTestSound(false)
                        }
                    },
                    label = { Text("Assign User 2 ($targetLangName)") },
                    leadingIcon = if (isAssignedToB) { { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) } } else null
                )
            }
            if (isAssignedToA || isAssignedToB) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { playTestSound(isAssignedToA) },
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
