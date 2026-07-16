package com.example.model

/**
 * What the UI shows where a language flag belongs: the emoji where the
 * platform's text stack can render it, otherwise the uppercase language code
 * (e.g. "EN"). Data-driven per the architecture's gating rule — callers pass
 * `capabilities.extendedGlyphs`; there is no platform branching here.
 * (Phase 4 decision, design §8: web bundles no emoji font, flags drew as boxes.)
 */
fun Language.flagLabel(extendedGlyphs: Boolean): String =
    if (extendedGlyphs) flag else code.uppercase()
