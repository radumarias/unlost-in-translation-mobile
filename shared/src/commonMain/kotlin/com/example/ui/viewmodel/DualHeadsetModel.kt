package com.example.ui.viewmodel

// Selected-headset state. deviceA/deviceB are platform audio-output device IDs
// (Android AudioDeviceInfo.id); null = unset. Web has no dual-headset routing, so
// these stay null there (capabilities.dualHeadset = false).
data class DualHeadsetModel(
    val deviceA: Int? = null,
    val deviceB: Int? = null,
    val isDualHeadsetModeEnabled: Boolean = false
) {
    /**
     * The output device this line should play on, or null for the phone's default.
     *
     * [forSideA] is whose *ear* it is, not whose turn it was: the source side's
     * translation is heard by the other person, which is why the conversation
     * screen asks with `!item.speakA` where the translate screen asks with `false`
     * for the source line and `true` for the translation. Getting that inversion
     * wrong is silent — the words simply come out of the wrong headset — and it
     * was written out longhand in five places, each free to be wrong on its own.
     *
     * Null whenever routing is off, so callers do not repeat the check either.
     */
    fun deviceFor(forSideA: Boolean): Int? = when {
        !isDualHeadsetModeEnabled -> null
        forSideA -> deviceA
        else -> deviceB
    }
}
