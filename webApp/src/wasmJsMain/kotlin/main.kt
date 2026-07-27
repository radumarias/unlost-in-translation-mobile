import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.example.platform.createAppContainer
import com.example.ui.App
import com.example.ui.rememberTranslateViewModel
import com.example.ui.viewmodel.TranslateViewModel
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Web entry point (design §9). Mounts the shared [App] into the page body via
 * ComposeViewport (Kotlin/Wasm canvas). The web [createAppContainer] supplies
 * browser-backed services (localStorage history, Web Speech TTS + recognition,
 * no-op Wear / dual-headset).
 *
 * Voice input (Phase 4): recognition runs through `container.voiceInput`
 * (WebVoiceInput on Chromium in a secure context; elsewhere the mic UI is
 * hidden by `capabilities.voiceInput`). One session at a time — a new tap
 * aborts the in-flight one. The shared "Speak now" prompt is surfaced as a
 * snackbar (the web has no system recognizer dialog), results route through
 * the shared `onVoiceInputResult`, and hard failures ([VoiceInputException])
 * surface their message as a snackbar — mirroring MainActivity's catch around
 * the RecognizerIntent launch.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Lives outside the composition: plain state for the entry-point lambda,
    // not something the UI observes.
    var activeVoiceJob: Job? = null
    var activeImageJob: Job? = null

    ComposeViewport(document.body!!) {
        // remember: the container (Ktor client, localStorage repository) must be
        // built once, not on recomposition of the root — mirrors MainActivity.
        val container = remember { createAppContainer() }
        val viewModel = rememberTranslateViewModel(container)
        val scope = rememberCoroutineScope()
        App(
            container = container,
            launchVoiceInput = { isConversationA, language ->
                // One recognition at a time: cancelling the previous job aborts
                // its browser session via recognize()'s invokeOnCancellation.
                activeVoiceJob?.cancel()
                activeVoiceJob = scope.launch {
                    val prompt = TranslateViewModel.voiceInputPrompt(language)
                    viewModel.showMessage(prompt)
                    val spokenText = try {
                        container.voiceInput.recognize(
                            languageTag = language.languageTag,
                            prompt = prompt,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        viewModel.showMessage(e.message ?: "Voice input failed.")
                        null
                    }
                    viewModel.onVoiceInputResult(spokenText, isConversationA)
                }
            },
            launchImageInput = { source ->
                // Same one-at-a-time rule as voice: a second pick supersedes an
                // abandoned file dialog, which the browser never reports.
                activeImageJob?.cancel()
                activeImageJob = scope.launch {
                    val picked = container.imagePicker.pick(source)
                    viewModel.onImageInputResult(picked?.base64, picked?.mimeType ?: "")
                }
            },
        )
    }
}
