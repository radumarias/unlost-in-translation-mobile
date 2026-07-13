package com.example.platform

import android.content.Context
import com.example.BuildConfig
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

    override val api: TranslationApi = KtorTranslationApi(
        apiKey = BuildConfig.GEMINI_API_KEY,
        client = translationHttpClient(OkHttp.create()),
    )

    override val historyRepository: TranslationHistoryRepository =
        RoomTranslationHistoryRepository(TranslationDatabase.getDatabase(appContext).translationDao())

    override val speechSynthesizer: SpeechSynthesizer = AndroidSpeechSynthesizer(ttsHelper)

    override val audioOutputCatalog: AudioOutputCatalog = AndroidAudioOutputCatalog(appContext)

    override val broadcaster: TranslationBroadcaster = WearTranslationBroadcaster(appContext)

    override val log: AppLog = AndroidAppLog()

    override val capabilities: PlatformCapabilities = PlatformCapabilities(
        voiceInput = true,
        dualHeadset = true,
        wearSync = true,
    )
}
