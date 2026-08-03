package com.example.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thrown by [WebVoiceInput] for hard recognition failures (blocked microphone,
 * no network, unsupported language). [code] is the browser's own
 * SpeechRecognition error code, **not** a sentence: the entry point turns it
 * into a snackbar through `Strings.voiceInputError`, so the wording follows the
 * device language. [message] exists for logs only.
 */
class VoiceInputException(val code: String) : RuntimeException("Voice input failed ($code)")

/**
 * Web [VoiceInput] backed by the Web Speech API's `SpeechRecognition`
 * (design §8, Phase 4). Feature-detected via [speechRecognitionAvailable] —
 * Chromium exposes it (as `webkitSpeechRecognition`) in secure contexts;
 * Firefox/Safari generally do not, so the container binds
 * [UnavailableVoiceInput] there and the mic UI stays hidden through
 * `capabilities.voiceInput`.
 *
 * One-shot recognition per call, mirroring the Android RecognizerIntent flow:
 * the final result resolves the suspension; "no-speech" and "aborted" resolve
 * null silently (the dialog-cancel equivalents); hard failures throw
 * [VoiceInputException] with a user-displayable message. Cancelling the
 * calling coroutine aborts the browser session. [prompt] has no browser UI to
 * host it (there is no system "Speak now" dialog on the web); callers surface
 * it as a snackbar instead.
 */
class WebVoiceInput(private val log: AppLog) : VoiceInput {

    private companion object {
        const val TAG = "WebVoiceInput"
    }

    override suspend fun recognize(languageTag: String, prompt: String): String? =
        suspendCancellableCoroutine { continuation ->
            // SpeechRecognition fires onend after both onresult and onerror; resume
            // exactly once, letting whichever event carries the outcome win.
            var resumed = false
            fun resumeOnce(block: () -> Unit) {
                if (!resumed) {
                    resumed = true
                    block()
                }
            }
            val recognition = startRecognition(
                languageTag = languageTag,
                onResult = { text ->
                    resumeOnce { continuation.resume(text.trim().takeIf { it.isNotEmpty() }) }
                },
                onError = { error ->
                    resumeOnce {
                        if (isBenign(error)) {
                            // no-speech / aborted: the user simply didn't provide
                            // speech — the RecognizerIntent-cancel equivalent.
                            log.d(TAG, "Speech recognition ended without result: $error")
                            continuation.resume(null)
                        } else {
                            log.e(TAG, "Speech recognition failed: $error")
                            continuation.resumeWithException(VoiceInputException(error))
                        }
                    }
                },
                onEnd = { resumeOnce { continuation.resume(null) } },
            )
            continuation.invokeOnCancellation { abortRecognition(recognition) }
        }

    /**
     * True for the outcomes that are not failures at all — the user said
     * nothing, or the session was superseded. Everything else is reported.
     * Which sentence a reported code gets is the catalogue's business, not this
     * layer's (`Strings.voiceInputError`).
     */
    private fun isBenign(error: String): Boolean = error == "no-speech" || error == "aborted"
}

/**
 * True when the browser exposes the Web Speech API's recognizer (Chromium) in a
 * secure context — insecure origins keep the constructor but recognition can
 * never start, so the mic UI must stay hidden there.
 */
internal fun speechRecognitionAvailable(): Boolean =
    js(
        "typeof window !== 'undefined' && window.isSecureContext === true && " +
            "('SpeechRecognition' in window || 'webkitSpeechRecognition' in window)"
    )

/**
 * Starts a one-shot recognition and returns the recognition object so a
 * cancelled caller can abort it. Callbacks: [onResult] with the final
 * transcript (one result at resultIndex — interimResults is off), [onError]
 * with the DOM error code string, [onEnd] when the session closes (fires last
 * in every outcome).
 */
private fun startRecognition(
    languageTag: String,
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
    onEnd: () -> Unit,
): JsAny = js(
    """{
    var Ctor = window.SpeechRecognition || window.webkitSpeechRecognition;
    var recognition = new Ctor();
    recognition.lang = languageTag;
    recognition.interimResults = false;
    recognition.maxAlternatives = 1;
    recognition.onresult = function(event) {
        var result = event.results[event.resultIndex];
        onResult(result && result[0] ? String(result[0].transcript) : '');
    };
    recognition.onerror = function(event) {
        onError(event.error ? String(event.error) : 'unknown');
    };
    recognition.onend = function() { onEnd(); };
    recognition.start();
    return recognition;
}"""
)

private fun abortRecognition(recognition: JsAny): Unit = js("recognition.abort()")
