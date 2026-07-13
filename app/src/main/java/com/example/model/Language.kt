package com.example.model

/**
 * A supported UI language. [languageTag] is a BCP 47 tag (e.g. "en-US") used at
 * the TTS/speech-recognition boundary via `Locale.forLanguageTag(...)`. The tags
 * are the exact values the previous `Locale.toLanguageTag()` produced, so the
 * platform behavior is unchanged. Kept `Locale`-free so this model can move to
 * commonMain later.
 */
data class Language(
    val code: String,
    val name: String,
    val flag: String,
    val languageTag: String
) {
    companion object {
        val supportedLanguages = listOf(
            Language("en", "English", "🇺🇸", "en-US"),
            Language("es", "Spanish", "🇪🇸", "es-ES"),
            Language("fr", "French", "🇫🇷", "fr-FR"),
            Language("de", "German", "🇩🇪", "de-DE"),
            Language("zh", "Chinese", "🇨🇳", "zh-CN"),
            Language("ja", "Japanese", "🇯🇵", "ja-JP"),
            Language("ko", "Korean", "🇰🇷", "ko-KR"),
            Language("it", "Italian", "🇮🇹", "it-IT"),
            Language("pt", "Portuguese", "🇵🇹", "pt-PT"),
            Language("ru", "Russian", "🇷🇺", "ru-RU"),
            Language("hi", "Hindi", "🇮🇳", "hi-IN"),
            Language("ar", "Arabic", "🇸🇦", "ar-SA"),
            Language("ro", "Romanian", "🇷🇴", "ro-RO"),
            Language("nl", "Dutch", "🇳🇱", "nl-NL"),
            Language("tr", "Turkish", "🇹🇷", "tr-TR"),
            Language("th", "Thai", "🇹🇭", "th-TH")
        )

        fun getByCode(code: String): Language {
            return supportedLanguages.firstOrNull { it.code == code } ?: supportedLanguages[0]
        }
    }
}
