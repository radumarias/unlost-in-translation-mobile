package com.example.network

/**
 * A failure whose [message] was written for a user to read.
 *
 * Every other exception's message is written for a developer — a status line, a
 * stack of causes, or, in the case that prompted this type, kotlinx-serialization's
 * `Unexpected JSON token at offset 758: Expected end of the object or comma at
 * path: $` followed by a slice of the payload. Those went straight to the screen,
 * because the view model's error paths all read `e.message ?: strings.something`
 * and a parser exception always has a message.
 *
 * So the rule is inverted: the message reaches the user only when the app wrote it
 * for them, and everything else becomes a plain sentence with the detail left in
 * the log. `KtorTranslationApi` throws this for the messages it composes by hand —
 * the exhausted-quota explanation and Google's own `error.message` — and ordinary
 * [Exception] for everything else.
 */
class UserVisibleException(message: String) : Exception(message)

/**
 * The message to show for this failure: its own when it was written for a user,
 * [fallback] otherwise. Callers log the exception separately — this decides what
 * goes on screen, not what goes in the log.
 */
fun Throwable.userVisibleMessage(fallback: String): String =
    (this as? UserVisibleException)?.message?.takeIf { it.isNotBlank() } ?: fallback
