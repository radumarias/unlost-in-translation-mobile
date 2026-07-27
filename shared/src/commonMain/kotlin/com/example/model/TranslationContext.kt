package com.example.model

/**
 * The conversational and stylistic context a translation is performed in, ported
 * from the web build (`src/app/page.tsx` TONES/SITUATIONS + the `history`,
 * `tone` and `situation` fields its API routes take).
 *
 * Passed to the Gemini-backed calls only: the free Google endpoint translates a
 * bare string and has nowhere to put any of this. Everything here ends up as
 * prompt text — no extra network calls are made for it.
 */
data class TranslationContext(
    val tone: String = Tone.AUTO,
    val situation: String = Situation.GENERAL,
    val history: List<ContextTurn> = emptyList(),
) {
    /**
     * Whether the constraints require a model that can actually honour them.
     * Google's endpoint cannot, so a non-default tone or situation forces the
     * primary translation onto Gemini regardless of the AI-Enhanced toggle.
     */
    val needsAiModel: Boolean
        get() = tone != Tone.AUTO || situation != Situation.GENERAL
}

/**
 * One prior turn of a conversation. Rendered into the prompt as
 * `"<speakerLangName> Speaker: <text>"` — the exact line shape the web build
 * builds in `api/translate/route.ts`.
 */
data class ContextTurn(val speakerLangName: String, val text: String)

/**
 * A selectable preset. [id] is what goes into the prompt (and must stay the
 * web's verbatim id — it is product behavior); [label] is what the UI shows and
 * [icon] is an emoji shown only where the platform has an emoji font
 * (`PlatformCapabilities.extendedGlyphs`; the wasm canvas has none).
 */
data class Preset(val id: String, val icon: String, val label: String)

/** The seven conversation tones, ids and icons verbatim from the web build. */
object Tone {
    const val AUTO = "Auto"

    val presets = listOf(
        Preset(AUTO, "✨", "Auto Tone"),
        Preset("Casual", "😊", "Casual"),
        Preset("Formal", "👔", "Formal"),
        Preset("Business", "💼", "Business"),
        Preset("Playful", "🥳", "Playful"),
        Preset("Empathetic", "❤️", "Empathetic"),
        Preset("Direct", "🎯", "Direct"),
    )
}

/**
 * The five situations. A situation outranks the tone in the prompt: medical
 * precision beats a casual register (see the constraint block in
 * `KtorTranslationApi.contextBlock`).
 */
object Situation {
    const val GENERAL = "General"

    val presets = listOf(
        Preset(GENERAL, "🌍", "General"),
        Preset("Medical", "🏥", "Medical"),
        Preset("Dating", "❤️", "Dating"),
        Preset("Service", "🍽️", "Service"),
        Preset("Emergency", "🚨", "Emergency"),
    )
}

/** The preset for [id], falling back to the first (default) one. */
fun List<Preset>.byId(id: String): Preset = firstOrNull { it.id == id } ?: first()
