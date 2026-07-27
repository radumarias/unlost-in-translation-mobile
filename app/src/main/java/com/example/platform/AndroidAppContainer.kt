package com.example.platform

import android.content.Context
import com.example.AppSecrets
import com.example.data.RoomTranslationHistoryRepository
import com.example.data.TranslationDatabase
import com.example.data.TranslationHistoryRepository
import com.example.network.KtorTranslationApi
import com.example.network.TranslationApi
import com.example.network.translationHttpClient
import com.example.speech.TTSHelper
import io.ktor.client.engine.okhttp.OkHttp
import com.example.wear.TranslationBroadcaster
import com.example.wear.WearTranslationBroadcaster

/**
 * Android [AppContainer]. Built once in MainActivity from the application context
 * plus the two composable-scoped services (the [TTSHelper] and the activity-result
 * [VoiceInput]) that must be created inside composition / against the Activity.
 * Everything else is process-scoped.
 */
class AndroidAppContainer(
    context: Context,
    ttsHelper: TTSHelper,
    override val voiceInput: VoiceInput,
) : AppContainer {

    private val appContext = context.applicationContext

    // Declared before `api`, which captures it at construction.
    override val log: AppLog = AndroidAppLog()

    override val api: TranslationApi = KtorTranslationApi(
        apiKey = AppSecrets.geminiApiKey,
        client = translationHttpClient(OkHttp.create()),
        log = log,
    )

    override val historyRepository: TranslationHistoryRepository =
        RoomTranslationHistoryRepository(TranslationDatabase.getDatabase(appContext).translationDao())

    override val speechSynthesizer: SpeechSynthesizer = AndroidSpeechSynthesizer(ttsHelper)

    // Like voiceInput, the live path is MainActivity's activity-result
    // launchers — a picker needs an Activity, not an application context.
    override val imagePicker: ImagePicker = UnavailableImagePicker

    override val audioOutputCatalog: AudioOutputCatalog = AndroidAudioOutputCatalog(appContext)

    override val broadcaster: TranslationBroadcaster = WearTranslationBroadcaster(appContext)

    override val platformActions: PlatformActions = AndroidPlatformActions(appContext)

    override val capabilities: PlatformCapabilities = PlatformCapabilities(
        voiceInput = true,
        dualHeadset = true,
        wearSync = true,
        imageInput = true,
        extendedGlyphs = true,
    )
}
