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

        /** Sentinel code for "let the backend work out what this is". */
        const val AUTO_CODE = "auto"

        /**
         * The auto-detect source option (web-feature port). Deliberately kept
         * out of [supportedLanguages]: it is a request, not a language, and it
         * has no BCP-47 tag, so it must never reach TTS or the target picker.
         */
        val autoDetect = Language(AUTO_CODE, "Auto-detect", "🌐", "")

        /** The picker's list; only the *source* picker offers auto-detect. */
        fun pickerLanguages(includeAuto: Boolean): List<Language> =
            if (includeAuto) listOf(autoDetect) + supportedLanguages else supportedLanguages

        /** The language for [code], or null when it is not one we support. */
        fun findByCode(code: String): Language? = when {
            code.equals(AUTO_CODE, ignoreCase = true) -> autoDetect
            else -> supportedLanguages.firstOrNull { it.code.equals(code, ignoreCase = true) }
        }

        fun getByCode(code: String): Language {
            return findByCode(code) ?: supportedLanguages[0]
        }
    }
}
