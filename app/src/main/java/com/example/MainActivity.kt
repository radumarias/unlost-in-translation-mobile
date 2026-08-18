package com.example

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import com.example.model.Language
import com.example.platform.AndroidAppContainer
import com.example.platform.AndroidImageLoading
import com.example.platform.AndroidQrScanner
import com.example.platform.AppContainer
import com.example.platform.CameraCapture
import com.example.platform.ImageSource
import com.example.ui.i18n.withArg
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
            val userStr = if (isConversationA) viewModel.strings.user1 else viewModel.strings.user2
            viewModel.showMessage(viewModel.strings.listeningOnHeadset.withArg(userStr))
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.languageTag)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, language.languageTag)
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(language.languageTag))
                putExtra(RecognizerIntent.EXTRA_PROMPT, viewModel.voiceInputPrompt(language))
            }
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            viewModel.showMessage(viewModel.strings.voiceInputUnsupported)
        }
    }

    // Image OCR translation (web-feature port). Two Activity-scoped launchers,
    // same reason voice input needs one. No CAMERA permission: TakePicture
    // hands off to the camera app, and PickVisualMedia is the permissionless
    // photo picker (backported below Android 13 by the Play services module).
    fun readPickedImage(uri: Uri?) {
        if (uri == null) return // the user backed out
        val base64 = AndroidImageLoading.toBase64Jpeg(context, uri)
        if (base64 == null) {
            viewModel.showMessage(viewModel.strings.imageReadFailed)
            return
        }
        viewModel.onImageInputResult(base64, AndroidImageLoading.MIME_TYPE)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> readPickedImage(uri) }

    // TakePicture writes into a file we own and reports success, not a Uri, so
    // the destination has to be held across the launch.
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { saved ->
        readPickedImage(pendingCameraUri.takeIf { saved })
        pendingCameraUri = null
    }

    fun launchImageInput(source: ImageSource) {
        try {
            when (source) {
                ImageSource.GALLERY -> galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
                ImageSource.CAMERA -> {
                    val uri = CameraCapture.newImageUri(context)
                    pendingCameraUri = uri
                    cameraLauncher.launch(uri)
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Image input failed to launch", e)
            pendingCameraUri = null
            viewModel.showMessage(viewModel.strings.imageInputUnavailable)
        }
    }

    // Pairing QR scanner (issue #33). Activity-scoped for the same reason as the
    // launchers above: the GMS Code Scanner takes an Activity. It is a suspending
    // call rather than a result launcher, so it needs a scope rather than a
    // `rememberLauncherForActivityResult`.
    val scope = rememberCoroutineScope()
    val qrScanner = remember(context) {
        // Unwrapped rather than cast: `LocalContext.current` is a ContextWrapper
        // under a themed context and under Robolectric, and a bare `as?` there
        // turns the scan button into a control that does nothing at all.
        context.findActivity()?.let { AndroidQrScanner(it, container.log) }
    }

    // Pairing deep link (issue #33): the app was opened by scanning someone's QR with
    // a camera app, or a link was tapped. The activity is singleTop, so a code scanned
    // while it is already running arrives through onNewIntent rather than a second
    // instance — hence the listener as well as the initial intent.
    //
    // The Uri is consumed once and cleared from the Intent: an Activity keeps its
    // Intent across a configuration change, and re-reading it on every rotation would
    // rejoin a session the user may have already left.
    // ComponentActivity, not Activity: onNewIntent listeners are androidx.activity's,
    // and under Robolectric the host is a ComponentActivity too.
    val activity = remember(context) { context.findActivity() as? ComponentActivity }
    LaunchedEffect(activity) {
        val current = activity ?: return@LaunchedEffect
        fun consume(intent: Intent?) {
            val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
            intent.data = null
            viewModel.joinFromLink(data.toString())
        }
        consume(current.intent)
        val listener = androidx.core.util.Consumer<Intent> { consume(it) }
        current.addOnNewIntentListener(listener)
        try {
            awaitCancellation()
        } finally {
            current.removeOnNewIntentListener(listener)
        }
    }

    fun launchQrScan() {
        val scanner = qrScanner
        if (scanner == null) {
            // Every other launcher here says why it did not run; this one used to
            // return in silence.
            viewModel.showMessage(viewModel.strings.pairingScanUnavailable)
            return
        }
        scope.launch {
            val payload = scanner.scan() ?: return@launch // cancelled, or no scanner
            if (!viewModel.joinRemoteSession(payload)) {
                viewModel.showMessage(viewModel.strings.pairingNotACode)
            }
        }
    }

    App(
        container = container,
        launchVoiceInput = ::launchVoiceInput,
        launchImageInput = ::launchImageInput,
        launchQrScan = ::launchQrScan,
    )
}

/** Walks the ContextWrapper chain; `LocalContext.current` is not always the Activity. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
