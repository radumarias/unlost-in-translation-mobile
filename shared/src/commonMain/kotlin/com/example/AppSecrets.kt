package com.example

/**
 * Public accessor for build-time secrets. Keeps the generated [Secrets] object
 * internal to :shared while letting consumers (the Android container / Wear
 * service, and later the web container) read the key. Falls back to the
 * placeholder when unset — the Gemini paths then surface the friendly
 * "not configured" error; the Google path is unaffected (design §6).
 */
object AppSecrets {
    val geminiApiKey: String get() = Secrets.GEMINI_API_KEY
}
