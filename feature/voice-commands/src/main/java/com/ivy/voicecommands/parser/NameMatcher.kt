package com.ivy.voicecommands.parser

import java.text.Normalizer
import java.util.Locale

/**
 * Matches spoken text against the names of categories and accounts,
 * ignoring case and diacritics.
 */
object NameMatcher {
    private val diacritics = Regex("\\p{Mn}+")

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(diacritics, "")
        .lowercase(Locale.ROOT)
        .trim()

    /**
     * Finds the item whose name best matches [name] (e.g. a name returned by an LLM).
     * Exact match first, then a name containing the other one.
     */
    fun <T> bestMatch(
        name: String,
        items: List<T>,
        itemName: (T) -> String,
    ): T? {
        val target = normalize(name)
        if (target.isEmpty()) return null
        val normalized = items.map { it to normalize(itemName(it)) }
        return normalized.firstOrNull { (_, candidate) -> candidate == target }?.first
            ?: normalized
                .filter { (_, candidate) ->
                    candidate.isNotEmpty() && (target.contains(candidate) || candidate.contains(target))
                }
                .maxByOrNull { (_, candidate) -> candidate.length }
                ?.first
    }

    /**
     * Finds the items whose names are mentioned as whole words in [text],
     * ordered by where they appear in the text.
     */
    fun <T> mentioned(
        text: String,
        items: List<T>,
        itemName: (T) -> String,
    ): List<T> {
        val normalizedText = normalize(text)
        return items
            .mapNotNull { item ->
                val name = normalize(itemName(item))
                if (name.isEmpty()) return@mapNotNull null
                val match = wordRegex(name).find(normalizedText) ?: return@mapNotNull null
                Triple(item, match.range.first, name.length)
            }
            // Prefer longer names when two names start at the same position (e.g. "Bank" vs "Bank Card")
            .sortedWith(compareBy<Triple<T, Int, Int>> { it.second }.thenByDescending { it.third })
            .distinctBy { it.second }
            .map { it.first }
    }

    fun wordRegex(phrase: String): Regex =
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(phrase)}(?![\\p{L}\\p{N}])")
}
