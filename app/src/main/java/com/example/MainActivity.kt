package com.example

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.model.Language
import com.example.platform.AndroidAppContainer
import com.example.platform.AppContainer
import com.example.platform.UnavailableVoiceInput
import com.example.speech.TTSHelper
import com.example.ui.App
import com.example.ui.rememberTranslateViewModel
import com.example.ui.viewmodel.TranslateViewModel

/**
 * Android entry point (design §9). Keeps only what needs an Activity/Context:
 * the [TTSHelper] lifecycle, the [AndroidAppContainer], and the RecognizerIntent
 * voice-input launcher. Everything else — the whole UI shell and view model —
 * lives in the shared [App] composable, which this hosts via `setContent`.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidApp()
        }
    }
}

@Composable
private fun AndroidApp() {
    val context = LocalContext.current

    // TTS management (composable-scoped; released on dispose).
    val ttsHelper = remember {
        TTSHelper(context) { success ->
            Log.d("MainActivity", "TTS Init success: $success")
        }
    }
    DisposableEffect(Unit) {
        onDispose { ttsHelper.release() }
    }

    // Hand-built platform container (design §3): the single place the UI/VM resolve
    // platform services. Web supplies a different AppContainer.
    val container: AppContainer = remember(ttsHelper) {
        AndroidAppContainer(context = context, ttsHelper = ttsHelper, voiceInput = UnavailableVoiceInput)
    }

    // Voice input still flows through this Activity-scoped launcher (design §8/§9);
    // the pendingVoiceRequestIsA conversation routing lives here. The VM comes from
    // the shared factory App also uses, so both resolve the same instance under this
    // Activity's ViewModelStoreOwner.
    val viewModel = rememberTranslateViewModel(container)

    var pendingVoiceRequestIsA by remember { mutableStateOf<Boolean?>(null) } // true: A, false: B, null: main mic

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            viewModel.onVoiceInputResult(spokenText, pendingVoiceRequestIsA)
        }
        pendingVoiceRequestIsA = null
    }

    fun launchVoiceInput(isConversationA: Boolean?, language: Language) {
        pendingVoiceRequestIsA = isConversationA

        val dualState = viewModel.dualHeadsetState.value
        if (dualState.isDualHeadsetModeEnabled && isConversationA != null) {
            val userStr = if (isConversationA) "User 1" else "User 2"
            viewModel.showMessage("Listening on $userStr's headset...")
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.languageTag)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, language.languageTag)
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(language.languageTag))
                putExtra(RecognizerIntent.EXTRA_PROMPT, TranslateViewModel.voiceInputPrompt(language))
            }
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            viewModel.showMessage(TranslateViewModel.VOICE_INPUT_UNSUPPORTED_MESSAGE)
        }
    }

    App(container = container, launchVoiceInput = ::launchVoiceInput)
}
