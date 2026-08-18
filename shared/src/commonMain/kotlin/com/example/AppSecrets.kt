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

    /**
     * Origin of the two-device conversation relay (issue #33), e.g.
     * `wss://uitr-relay.<subdomain>.workers.dev`, or blank when `.env` does not set
     * `RELAY_BASE_URL`. Unlike the Gemini key this is not a secret — it is
     * deployment configuration, kept out of the source so that moving the relay to a
     * production domain is a config change and not a release. Blank means remote
     * conversation mode is unconfigured and stays off.
     */
    val relayBaseUrl: String get() = Secrets.RELAY_BASE_URL

    /**
     * Origin of the deployed web build, e.g. `https://translate.example.com`, or blank
     * when `.env` does not set `WEB_BASE_URL`. Also not a secret. It exists so the
     * pairing QR can carry an `https://<host>/j#…` link instead of the compact
     * `uitr:` form: a link is what a stock camera app can act on, opening the
     * installed Android app through an App Link and the web build when there is none.
     * Blank keeps the QR compact, which is smaller and works only for this app's own
     * scanner — the behaviour before deep links existed.
     */
    val webBaseUrl: String get() = Secrets.WEB_BASE_URL
}
