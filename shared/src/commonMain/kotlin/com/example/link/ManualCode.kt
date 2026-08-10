package com.example.link

/**
 * The six-character pairing code people read out loud when a camera is not an
 * option (issue #33, "Discovery: QR + 6-character manual code fallback").
 *
 * Crockford's base32 alphabet: no `I`, `L`, `O` or `U`, so nothing in it can be
 * misheard or mistyped as something else, and no accidental word appears. Six
 * characters carry **30 bits**, which issue #34 §5 already faces squarely — the code
 * is not a secret, the manual path is MITM-able by the relay, and the mitigation is
 * the 5-digit SAS both people compare before any turn is rendered. Nothing here
 * pretends otherwise.
 */
internal object ManualCode {
    /** Crockford base32, minus the letters that get confused when spoken or typed. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 6

    /** The number of distinct codes: 32^6 = 2^30. */
    const val SPACE: Long = 1L shl 30

    /** Formats [value] (`0 until SPACE`) as six characters. */
    fun format(value: Long): String {
        require(value in 0 until SPACE) { "manual code value out of range" }
        val out = CharArray(LENGTH)
        var rest = value
        for (i in LENGTH - 1 downTo 0) {
            out[i] = ALPHABET[(rest and 31L).toInt()]
            rest = rest shr 5
        }
        return out.concatToString()
    }

    /**
     * Parses a typed code, or null when it is not one.
     *
     * Forgiving in exactly the ways a person typing is: case, spaces and dashes are
     * ignored, and the four look-alike characters Crockford excludes are folded onto
     * the ones they resemble (`I`/`L` → `1`, `O` → `0`, `U` → `V`). So "u5-h2 ok"
     * and "V5H20K" are the same code.
     */
    fun parseOrNull(text: String): Long? {
        val cleaned = buildString {
            for (raw in text) {
                if (raw == ' ' || raw == '-' || raw == '\t') continue
                append(
                    when (val c = raw.uppercaseChar()) {
                        'I', 'L' -> '1'
                        'O' -> '0'
                        'U' -> 'V'
                        else -> c
                    }
                )
            }
        }
        if (cleaned.length != LENGTH) return null
        var value = 0L
        for (c in cleaned) {
            val digit = ALPHABET.indexOf(c)
            if (digit < 0) return null
            value = (value shl 5) or digit.toLong()
        }
        return value
    }

    /** Normalized display form of [text], or null when it is not a valid code. */
    fun normalizeOrNull(text: String): String? = parseOrNull(text)?.let(::format)
}
