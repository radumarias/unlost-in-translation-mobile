package com.example.platform

/**
 * Web [AudioOutputCatalog]: the browser has no per-device audio routing, so there
 * are no selectable outputs and the test tone is a no-op. `capabilities.dualHeadset`
 * is false on web, so this is never reached from the UI — it exists only to satisfy
 * the [AppContainer] contract.
 */
object WebAudioOutputCatalog : AudioOutputCatalog {
    override fun listOutputDevices(): List<AudioOutputDevice> = emptyList()
    override fun playTestTone(deviceId: Int) { /* no routable outputs on web */ }
}
