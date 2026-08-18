package com.example.platform

import kotlinx.browser.window

/**
 * Reads a pairing link out of the page URL and takes it out of the URL again.
 *
 * This is the browser half of the deep link: the pairing QR carries
 * `https://<host>/j#s=…`, so a phone that scans it with its stock camera app opens
 * the installed Android app through the App Link, and this build when there is no
 * app installed. Both ends then hand the same payload to
 * `TranslateViewModel.joinFromLink`.
 *
 * Everything is in the fragment, which no browser sends to a server — that is the
 * property the whole QR design rests on (see `SessionTicket`). It does, however, sit
 * in the address bar and in the browsing history, so it is removed as soon as it has
 * been read: `replaceState` rewrites the entry rather than pushing a new one, so Back
 * does not walk into a URL that still carries the session secret. The cost is that a
 * join which fails needs the QR scanned again rather than a reload, which is one tap
 * and is the right trade against leaving a secret in the history of a shared phone.
 *
 * Returns null when the page was opened without a fragment, which is the ordinary case.
 */
fun consumePairingLinkFromUrl(): String? {
    val hash = window.location.hash
    if (hash.isEmpty() || hash == "#") return null
    val href = window.location.href
    window.history.replaceState(null, "", window.location.pathname + window.location.search)
    return href
}
