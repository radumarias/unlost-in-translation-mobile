package com.example.ui.viewmodel

import android.media.AudioDeviceInfo

// Mock of selected headsets
data class DualHeadsetModel(
    val deviceA: Int? = null,
    val deviceB: Int? = null,
    val isDualHeadsetModeEnabled: Boolean = false
)
