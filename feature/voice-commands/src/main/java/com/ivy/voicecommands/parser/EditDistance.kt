package com.ivy.voicecommands.parser

/**
 * Levenshtein distance: the number of single-character edits that turn [a] into [b].
 */
internal fun editDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        for (j in 1..b.length) {
            val substitution = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(
                previous[j] + 1,
                current[j - 1] + 1,
                previous[j - 1] + substitution
            )
        }
        previous = current
    }
    return previous[b.length]
}
