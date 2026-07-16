import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.example.platform.createAppContainer
import com.example.ui.App
import kotlinx.browser.document

/**
 * Web entry point (design §9). Mounts the shared [App] into the page body via
 * ComposeViewport (Kotlin/Wasm canvas). The web [createAppContainer] supplies
 * browser-backed services (localStorage history, Web Speech TTS, no-op Wear /
 * dual-headset). Voice input is capability-gated off for the MVP, so the
 * launch-voice-input callback is a no-op — the mic buttons are hidden by
 * `capabilities.voiceInput = false`.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        // remember: the container (Ktor client, localStorage repository) must be
        // built once, not on recomposition of the root — mirrors MainActivity.
        val container = remember { createAppContainer() }
        App(
            container = container,
            launchVoiceInput = { _, _ -> },
        )
    }
}
