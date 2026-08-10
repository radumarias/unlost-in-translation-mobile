package com.example.platform

import android.content.Context
import com.example.AppSecrets
import com.example.data.RoomTranslationHistoryRepository
import com.example.data.TranslationDatabase
import com.example.data.TranslationHistoryRepository
import com.example.link.ConversationLink
import com.example.link.OfflineConversationLink
import com.example.link.WebSocketConversationLink
import com.example.link.linkHttpClient
import com.example.network.KtorTranslationApi
import com.example.network.TranslationApi
import com.example.network.translationHttpClient
import com.example.speech.TTSHelper
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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

    /**
     * Blank when this build has no `RELAY_BASE_URL`, in which case there is nothing
     * to connect to and the capability below turns the feature off rather than
     * offering a pairing screen that could only fail.
     */
    private val relayBaseUrl: String = AppSecrets.relayBaseUrl

    /**
     * Process-scoped, not composition-scoped: a paired session has to survive the
     * view model being recreated on rotation.
     */
    override val conversationLink: ConversationLink =
        if (relayBaseUrl.isBlank()) {
            OfflineConversationLink
        } else {
            WebSocketConversationLink(
                client = linkHttpClient(OkHttp.create()),
                relayBaseUrl = relayBaseUrl,
                scope = CoroutineScope(SupervisorJob()),
                log = log,
            )
        }

    // Like voiceInput and imagePicker: the GMS Code Scanner needs an Activity, so
    // the live path is a launcher in MainActivity.
    override val qrScanner: QrScanner = UnavailableQrScanner

    override val platformActions: PlatformActions = AndroidPlatformActions(appContext)

    override val capabilities: PlatformCapabilities = PlatformCapabilities(
        voiceInput = true,
        dualHeadset = true,
        wearSync = true,
        imageInput = true,
        extendedGlyphs = true,
        remoteConversation = relayBaseUrl.isNotBlank(),
    )
}
