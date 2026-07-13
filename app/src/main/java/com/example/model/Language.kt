package com.example.model

import java.util.Locale

data class Language(
    val code: String,
    val name: String,
    val flag: String,
    val locale: Locale
) {
    companion object {
        val supportedLanguages = listOf(
            Language("en", "English", "🇺🇸", Locale.US),
            Language("es", "Spanish", "🇪🇸", Locale("es", "ES")),
            Language("fr", "French", "🇫🇷", Locale.FRANCE),
            Language("de", "German", "🇩🇪", Locale.GERMANY),
            Language("zh", "Chinese", "🇨🇳", Locale.CHINA),
            Language("ja", "Japanese", "🇯🇵", Locale.JAPAN),
            Language("ko", "Korean", "🇰🇷", Locale.KOREA),
            Language("it", "Italian", "🇮🇹", Locale.ITALY),
            Language("pt", "Portuguese", "🇵🇹", Locale("pt", "PT")),
            Language("ru", "Russian", "🇷🇺", Locale("ru", "RU")),
            Language("hi", "Hindi", "🇮🇳", Locale("hi", "IN")),
            Language("ar", "Arabic", "🇸🇦", Locale("ar", "SA")),
            Language("ro", "Romanian", "🇷🇴", Locale("ro", "RO")),
            Language("nl", "Dutch", "🇳🇱", Locale("nl", "NL")),
            Language("tr", "Turkish", "🇹🇷", Locale("tr", "TR")),
            Language("th", "Thai", "🇹🇭", Locale("th", "TH"))
        )

        fun getByCode(code: String): Language {
            return supportedLanguages.firstOrNull { it.code == code } ?: supportedLanguages[0]
        }
    }
}
