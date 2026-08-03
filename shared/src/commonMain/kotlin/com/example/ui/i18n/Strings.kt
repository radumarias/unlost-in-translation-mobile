package com.example.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import com.example.ui.i18n.locales.arabic
import com.example.ui.i18n.locales.chinese
import com.example.ui.i18n.locales.dutch
import com.example.ui.i18n.locales.french
import com.example.ui.i18n.locales.german
import com.example.ui.i18n.locales.hindi
import com.example.ui.i18n.locales.italian
import com.example.ui.i18n.locales.japanese
import com.example.ui.i18n.locales.korean
import com.example.ui.i18n.locales.portuguese
import com.example.ui.i18n.locales.romanian
import com.example.ui.i18n.locales.russian
import com.example.ui.i18n.locales.spanish
import com.example.ui.i18n.locales.thai
import com.example.ui.i18n.locales.turkish

/**
 * Every user-visible string in the shared UI, in one place.
 *
 * Each field defaults to the English text the composable used to hold inline, so
 * a locale is written as a [Strings] copy that overrides only what it actually
 * translates — anything it leaves out falls back to English automatically. That
 * matters here: the web build this catalogue was ported from has uneven coverage
 * (`cameraTranslation` exists in 5 of its 13 locales), and a half-translated
 * locale should degrade to English rather than to a blank label.
 *
 * A data class rather than a `Map<String, String>` on purpose: the field names
 * are checked by the compiler, so a renamed or deleted string breaks the build
 * instead of silently rendering an empty box at runtime.
 *
 * Language *names* are deliberately NOT in here. `Language.name` is also what
 * goes into the Gemini prompts, so it has to stay English; translating the names
 * shown in the picker is the separate "endonyms" item (Tier C, out of scope).
 *
 * Placeholders are `{0}` / `{1}`, substituted with [withArg] / [withArgs].
 */
data class Strings(
    // --- Top bar ---
    val appName: String = "Translate",
    val logoDescription: String = "Logo",
    val aiModeDescription: String = "AI Mode",
    val engineGemini: String = "Gemini Translation",
    val engineStandard: String = "Standard Engine",

    // --- Language bar and picker ---
    /** Source pill once auto-detect has resolved; `{0}` is the detected language. */
    val autoDetectedLabel: String = "Auto - {0}",
    val selectSourceLanguage: String = "Select language",
    val selectTargetLanguage: String = "Select target language",
    val swapLanguages: String = "Swap Languages",
    val pickerTitleSource: String = "Translate from",
    val pickerTitleTarget: String = "Translate to",
    val closePicker: String = "Close Picker",
    val searchLanguagePlaceholder: String = "Search language...",
    val search: String = "Search",
    val selected: String = "Selected",

    // --- Tone / situation ---
    val toneLabel: String = "Tone",
    val situationLabel: String = "Situation",
    /** Dropdown affordance; `{0}` is [toneLabel] or [situationLabel]. */
    val selectPreset: String = "Select {0}",
    val toneAuto: String = "Auto Tone",
    val toneCasual: String = "Casual",
    val toneFormal: String = "Formal",
    val toneBusiness: String = "Business",
    val tonePlayful: String = "Playful",
    val toneEmpathetic: String = "Empathetic",
    val toneDirect: String = "Direct",
    val situationGeneral: String = "General",
    val situationMedical: String = "Medical",
    val situationDating: String = "Dating",
    val situationService: String = "Service",
    val situationEmergency: String = "Emergency",

    // --- Bottom dock ---
    val conversationModeDescription: String = "Conversation split-mic Mode",
    val recordSpeech: String = "Record Speech",
    val translatePhoto: String = "Translate a photo",
    val takePhoto: String = "Take a photo",
    val chooseFromGallery: String = "Choose from gallery",
    val quickTextInput: String = "Quick text input action",
    val tabTranslate: String = "Translate",
    val tabHistory: String = "History",
    val tabSaved: String = "Saved",
    val tabTranslateDescription: String = "Translate Tab Button",
    val tabHistoryDescription: String = "History Tab Button",
    val tabSavedDescription: String = "Saved Tab Button",

    // --- Translate screen ---
    val inputPlaceholder: String = "Enter text to translate...",
    val clear: String = "Clear",
    val hearInput: String = "Hear input",
    val translateAction: String = "Translate",
    val translating: String = "Translating...",
    val readingImage: String = "Reading image...",
    val favorite: String = "Favorite",
    val roundTripDescription: String = "Round trip",
    val roundTripTitle: String = "Translates back as:",
    val perceivedMeaningDescription: String = "Perceived meaning",
    val perceivedMeaningTitle: String = "They will read it as:",
    val validatingCulturalMeaning: String = "Validating cultural meaning...",
    val noCulturalIssues: String =
        "The translation is accurate. No significant cultural nuances or warnings detected.",
    val culturalContextDescription: String = "Context",
    val culturalWarning: String = "Cultural Warning",
    val culturalContext: String = "Cultural Context",
    val tapForDetails: String = "Tap to see details and suggestions",
    val speakTranslation: String = "Speak translation",
    /** Opens the translation full-screen, to show it to the other person. */
    val showFullScreen: String = "Show full screen",
    /** Swaps the languages and clears the workspace for the other person's answer. */
    val handOverForReply: String = "Their turn to reply",
    val copyText: String = "Copy text",
    val share: String = "Share",
    /** Title of the Android share sheet. The web share API shows no title. */
    val shareTranslationTitle: String = "Share translation",
    val errorDescription: String = "Error",
    val copiedTranslation: String = "Copied translation!",
    val addedToStarHistory: String = "Added to star history.",
    /** Loaded by the quick-edit button when the input is empty. */
    val sampleText: String = "Hello, how are you?",

    // --- Validation details ---
    val detailsDescription: String = "Details",
    val close: String = "Close",
    val explanation: String = "Explanation",
    val howTheyWillReadIt: String = "How they will read it",
    val idiomOrLocalExpression: String = "Idiom or local expression",
    val suggestedAlternatives: String = "Suggested alternatives:",
    val suggestionDescription: String = "Suggestion",
    val importantToneWarning: String = "Important Tone Warning",
    val culturalDetails: String = "Cultural Details",

    // --- Rewrite ---
    val rewriteTitle: String = "Rewrite what you said",
    val rewriteBlurb: String =
        "Ask for a version of your message that keeps the intent but avoids the problem above.",
    val rewriteAction: String = "Suggest a rewrite",
    val rewriting: String = "Rewriting...",
    val useThisVersion: String = "Use this version",
    val otherWaysToRewrite: String = "Or try another angle:",

    // --- History / saved ---
    val recentTranslations: String = "RECENT TRANSLATIONS",
    val savedTranslations: String = "SAVED TRANSLATIONS",
    val clearAll: String = "Clear All",
    val clearAllDescription: String = "Clear all",
    val emptyDescription: String = "Empty",
    val noHistory: String = "No translation history yet.",
    val noFavorites: String = "No favorite items saved.",
    val toggleFavorite: String = "Toggle favorite",
    val deletedFromHistory: String = "Deleted from history",

    // --- Conversation ---
    val back: String = "Back",
    val conversationMode: String = "Conversation Mode",
    val micA: String = "Mic A",
    val micB: String = "Mic B",
    /** Typed-input placeholder when voice input is unavailable; `{0}` is a language. */
    val typeInLanguage: String = "Type in {0}…",
    /** Send affordance for that field; `{0}` is a language. */
    val sendLanguageMessage: String = "Send {0} message",
    val confirmTranslation: String = "Confirm Translation",
    /** `{0}` is the speaker's language. */
    val youSaidIn: String = "You said ({0}):",
    /** `{0}` is the listener's language. */
    val translationIn: String = "Translation ({0}):",
    val validationInfo: String = "Validation Info",
    val cancel: String = "Cancel",
    val send: String = "Send",
    val pickLanguageToSpeak: String = "Pick a language to speak in, or type instead.",

    // --- Two-device conversation (issue #33) ---
    val pairDevices: String = "Use two phones",
    val pairingTitle: String = "Two phones, one conversation",
    val pairingBlurb: String =
        "Each of you holds your own phone. What you say is translated and appears on the other one.",
    val pairingShowThisCode: String = "Have the other person scan this",
    val pairingCantScan: String = "Can't scan it?",
    val pairingTypeCodeInstead: String = "Use a code instead",
    /** Label above the six-character code, which is displayed on its own line. */
    val pairingCodeIs: String = "Your code",
    val pairingEnterCode: String = "Enter the code from the other phone",
    val pairingCodePlaceholder: String = "6-character code",
    val pairingScanQr: String = "Scan the QR code",
    val pairingScanUnavailable: String = "The scanner cannot be opened on this phone.",
    val pairingJoin: String = "Join",
    val pairingWaiting: String = "Waiting for the other phone…",
    val pairingNotACode: String = "That is not a pairing code.",
    val pairingLeave: String = "Leave",
    // Connection banner, driven by the link's state.
    val linkConnecting: String = "Connecting…",
    /** Shown while the two phones exchange keys and confirm them (issue #34). */
    val linkSecuring: String = "Securing the connection…",
    val linkFailedEncryption: String =
        "The connection could not be secured, so the session was closed.",
    /** `{0}` is the peer's language. */
    val linkConnectedWith: String = "Connected — the other phone speaks {0}",
    val linkPeerLeft: String = "The other phone left the conversation.",
    val linkFailedNetwork: String = "Lost the connection to the other phone.",
    val linkFailedUnconfigured: String = "This build has no relay configured, so two-phone mode is off.",
    val linkFailedProtocol: String = "The other phone is speaking a version this app does not understand.",
    val linkThisPhone: String = "This phone",
    val linkOtherPhone: String = "Other phone",
    // Verification (issue #34 §5). The two states are told apart honestly: the QR
    // authenticated the other phone's key, a typed code cannot.
    val linkVerifiedByQr: String = "Encrypted, verified by the QR code",
    val linkNeedsSasCheck: String = "Encrypted — check the code matches",
    /** After the user says the five digits match. Still weaker than the QR path. */
    val linkVerifiedBySas: String = "Encrypted, you checked the code",
    val sasTitle: String = "Do both phones show this?",
    val sasBlurb: String =
        "You joined with a typed code, so the phones cannot verify each other on their own. " +
            "If the other phone shows different digits, someone is in the middle — close the session.",
    val sasMatches: String = "Yes, they match",
    val sasDoesNotMatch: String = "No — close the session",

    // --- Dual headset ---
    val dualHeadsetSetup: String = "Dual Headset Setup",
    val dualHeadphoneMode: String = "Dual Headphone Mode",
    val dualHeadsetBlurb: String =
        "Connect two sets of headphones. We will route each person's translations to their specific headphones.",
    val enableDualHeadsetRouting: String = "Enable Dual Headset Routing",
    val availableDevices: String = "Available Devices",
    val noHeadsetsFound: String = "No Bluetooth or Wired headsets found.",
    /** `{0}` is the device label, `{1}` the language it will speak. */
    val playingTestSound: String = "Playing test sound to {0} in {1}...",
    /** `{0}` is the party's language. */
    val assignUser1: String = "Assign User 1 ({0})",
    /** `{0}` is the party's language. */
    val assignUser2: String = "Assign User 2 ({0})",
    val testHeadphoneOutput: String = "Test Headphone Output",
    val user1: String = "User 1",
    val user2: String = "User 2",
    /** `{0}` is [user1] or [user2]. */
    val playingTranslationToHeadset: String = "Playing translation to {0}'s headset...",
    /** `{0}` is [user1] or [user2]. */
    val listeningOnHeadset: String = "Listening on {0}'s headset...",

    // --- Help ---
    // Written for this build, not copied from the web build: several of its
    // help sections describe behaviour that was deliberately not ported.
    val helpDescription: String = "Help",
    val helpTitle: String = "How to use this app",
    val helpVoiceTitle: String = "Voice and text",
    val helpVoiceShort: String = "Speak or type, and check what actually got across.",
    val helpVoiceBody: String =
        "Tap the microphone or just start typing. Under each translation you get two checks: " +
            "\"Translates back as\" is the words translated back literally, and \"They will read it as\" " +
            "is what the other person will actually take from it.",
    val helpCulturalTitle: String = "Cultural warnings and idioms",
    val helpCulturalShort: String = "Avoid the mistakes a literal translation makes.",
    val helpCulturalBody: String =
        "If a phrase would land badly in the other language, you get a warning instead of a " +
            "confident wrong answer. Idioms are flagged too, with how a native speaker would say the " +
            "same thing. Tap the banner for the details and a rewrite.",
    val helpToneTitle: String = "Tone and situation",
    val helpToneShort: String = "Adapt the register to where you are.",
    val helpToneBody: String =
        "The two pills above the input set the tone and the situation — Medical, Emergency, Dating " +
            "and so on. A situation outranks the tone: precision beats charm in a hospital. Picking " +
            "either turns AI Enhanced on, because the fast engine cannot express them.",
    val helpCameraTitle: String = "Camera translation",
    val helpCameraShort: String = "Photograph a sign or a menu.",
    val helpCameraBody: String =
        "The camera button takes a photo or picks one from the gallery, reads the text in it and " +
            "translates it. The photo is not saved.",
    val helpAutoDetectTitle: String = "Auto-detect",
    val helpAutoDetectShort: String = "When you do not know what language you are looking at.",
    val helpAutoDetectBody: String =
        "Set the source language to Auto-detect and the app works out what was written. The " +
            "selection stays on Auto-detect afterwards — it reports what it found next to it, so " +
            "several messages in an unknown language cost you nothing extra.",
    val helpConversationTitle: String = "Conversation mode",
    val helpConversationShort: String = "Two people, one phone, taking turns.",
    val helpConversationBody: String =
        "Conversation mode gives each side its own button and keeps the exchange on screen. Every " +
            "turn is shown to you for approval before it goes out, and the last few turns are used " +
            "as context so pronouns and short answers still make sense.",
    val helpTwoPhonesTitle: String = "Two phones",
    val helpTwoPhonesShort: String = "Each of you holds your own.",
    val helpTwoPhonesBody: String =
        "Pair by showing the QR code to the other phone, or by reading out a six-character code. " +
            "What you say is translated here and appears there. The messages between the two " +
            "phones are end-to-end encrypted, so the server that carries them cannot read them — " +
            "but the translation itself is done by Google or Gemini before it is sent, exactly as " +
            "it is everywhere else in this app. Encryption hides the hop between the phones, not " +
            "the translation.",
    val helpDualHeadsetTitle: String = "Two headsets",
    val helpDualHeadsetShort: String = "Each person hears only their own side.",
    val helpDualHeadsetBody: String =
        "Connect two sets of headphones and assign one to each person. Each translation is then " +
            "played only into the headphones of the person it is meant for.",
    val helpFullScreenTitle: String = "Show it to them",
    val helpFullScreenShort: String = "Make the translation big enough to read across a table.",
    val helpFullScreenBody: String =
        "The full-screen button on any translation fills the screen with it, so you can hold the " +
            "phone up instead of reading it out. Tap anywhere to come back.",

    // --- Voice input ---
    /** `{0}` is the language being listened for. Android shows it in the
     *  RecognizerIntent dialog, the web as a snackbar. */
    val voiceInputPrompt: String = "Speak now ({0})...",
    val voiceInputUnsupported: String = "Voice input is not supported on this device.",
    val voiceInputFailed: String = "Voice input failed.",
    // Hard browser-recognition failures, keyed by the DOM error code through
    // [voiceInputError]. The platform layer reports the code; the wording is
    // chosen here, so it follows the device language like everything else.
    val voiceInputMicBlocked: String =
        "Microphone access is blocked. Allow it in the browser settings.",
    val voiceInputNoMicrophone: String = "No microphone was found.",
    val voiceInputNoNetwork: String = "Voice input needs a network connection.",
    val voiceInputLanguageUnsupported: String =
        "Voice input does not support this language in this browser.",
    /** `{0}` is the browser's own error code, kept so an unknown failure is still reportable. */
    val voiceInputFailedWithCode: String = "Voice input failed ({0}).",

    // --- Image input ---
    /** No camera or gallery app answered the launch — Android only. */
    val imageInputUnavailable: String = "No app is available to provide a photo.",

    // --- View-model messages ---
    val aiEnhancedTurnedOn: String = "AI Enhanced turned on — tone and situation need it.",
    val translateFirstToDetect: String = "Translate something first so the language can be detected.",
    val pickLanguageBeforeConversation: String =
        "Pick a language for your side before starting a conversation.",
    val translationFailed: String = "Failed to perform translation",
    /** `{0}` is the underlying error. Quota errors bypass this and speak for themselves. */
    val validationFailed: String = "Validation failed: {0}",
    /** When the failure has no message written for a user — a parse error, say. */
    val validationUnavailable: String = "The cultural check could not be completed.",
    val imageReadFailed: String = "Failed to read the image",
    val rewriteFailed: String = "Failed to rewrite the message",
    val conversationTranslationFailed: String = "Failed at translation stage",
) {
    /**
     * The label for a tone or situation preset. Keyed by [com.example.model.Preset.id],
     * which is prompt text and therefore always English — only the label moves.
     */
    fun preset(id: String): String = when (id) {
        "Auto" -> toneAuto
        "Casual" -> toneCasual
        "Formal" -> toneFormal
        "Business" -> toneBusiness
        "Playful" -> tonePlayful
        "Empathetic" -> toneEmpathetic
        "Direct" -> toneDirect
        "General" -> situationGeneral
        "Medical" -> situationMedical
        "Dating" -> situationDating
        "Service" -> situationService
        "Emergency" -> situationEmergency
        else -> id
    }

    /**
     * The message for a hard voice-input failure, keyed by the browser's own
     * SpeechRecognition error code. The platform layer used to compose these
     * sentences itself, which left them English on a translated device; it now
     * hands up the code and the wording is picked here. An unrecognised code
     * stays visible in the message so a failure is still reportable.
     */
    fun voiceInputError(code: String): String = when (code) {
        "not-allowed", "service-not-allowed" -> voiceInputMicBlocked
        "audio-capture" -> voiceInputNoMicrophone
        "network" -> voiceInputNoNetwork
        "language-not-supported" -> voiceInputLanguageUnsupported
        else -> voiceInputFailedWithCode.withArg(code)
    }
}

/** Substitutes the single `{0}` placeholder. */
fun String.withArg(value: String): String = replace("{0}", value)

/** Substitutes `{0}` and `{1}`. */
fun String.withArgs(first: String, second: String): String =
    replace("{0}", first).replace("{1}", second)

/**
 * Locales other than English, keyed by ISO 639-1 code — one per language this
 * app can translate into, so the UI can speak whichever of them the device is
 * set to. [stringsFor] falls back to English for any other device language.
 */
internal val localeCatalogs: Map<String, Strings> = mapOf(
    "es" to spanish,
    "ro" to romanian,
    "fr" to french,
    "de" to german,
    "it" to italian,
    "pt" to portuguese,
    "ru" to russian,
    "ja" to japanese,
    "zh" to chinese,
    "ko" to korean,
    "ar" to arabic,
    "th" to thai,
    "nl" to dutch,
    "tr" to turkish,
    "hi" to hindi,
)

/**
 * The catalogue for a device language tag (`"ro"`, `"pt-BR"`, `null`), English
 * when the language is not translated.
 */
fun stringsFor(languageTag: String?): Strings {
    val code = languageTag?.substringBefore('-')?.lowercase().orEmpty()
    return localeCatalogs[code] ?: Strings()
}

/**
 * The catalogue in scope. Defaults to English so a composable rendered outside
 * [com.example.ui.App] — a preview, a screenshot test — still has text.
 */
val LocalStrings = staticCompositionLocalOf { Strings() }
