package com.example.ui.viewmodel

// Selected-headset state. deviceA/deviceB are platform audio-output device IDs
// (Android AudioDeviceInfo.id); null = unset. Web has no dual-headset routing, so
// these stay null there (capabilities.dualHeadset = false).
data class DualHeadsetModel(
    val deviceA: Int? = null,
    val deviceB: Int? = null,
    val isDualHeadsetModeEnabled: Boolean = false
)
