package com.example.model

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Which device a conversation turn came from. */
enum class TurnOrigin {
    /** Spoken into this phone. */
    LOCAL,

    /** Arrived from the paired device (issue #33). */
    REMOTE,
}

/**
 * One turn in a conversation. Promoted out of `TranslateViewModel` for issue #33,
 * because a turn now has to survive a trip over the wire and be recognised again
 * when the relay replays it after a reconnect.
 *
 * Three of these fields are the promotion:
 *
 * * [id] makes replay idempotent. The relay hands a reconnecting device the frames
 *   it missed, and without an id the same sentence would appear twice.
 * * [origin] is what lets the UI show which phone spoke, and what stops a turn that
 *   arrived from the peer being sent straight back to it.
 * * [srcLangCode] / [tgtLangCode] fix a latent defect: `conversationTurns()` used to
 *   label *historical* turns with the *current* language selection, so changing a
 *   picker mid-conversation silently mislabelled everything already said in the
 *   Gemini prompt context. With a peer that announces its own language that stops
 *   being theoretical (issue #33, finding #3).
 *
 * [speakA] is kept as it was — the conversation UI colours and aligns bubbles by it.
 */
data class ConversationItem(
    val text: String,
    val translated: String,
    /** True when Language A (this device's source language) spoke. */
    val speakA: Boolean,
    val srcLangCode: String,
    val tgtLangCode: String,
    val id: String = newId(),
    val origin: TurnOrigin = TurnOrigin.LOCAL,
    @OptIn(ExperimentalTime::class)
    val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
) {
    companion object {
        /**
         * A turn id. Only ever compared for equality, never guessed at and never
         * used as a credential, so `kotlin.random.Random` is the right tool here —
         * unlike a session id, which is a bearer secret and comes from a CSPRNG.
         */
        @OptIn(ExperimentalTime::class)
        fun newId(): String =
            "t-${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt(0, 1_000_000)}"
    }
}
