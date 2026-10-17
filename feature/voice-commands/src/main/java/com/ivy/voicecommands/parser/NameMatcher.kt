package com.ivy.voicecommands.parser

import java.text.Normalizer
import java.util.Locale

/**
 * Matches spoken text against the names of categories and accounts.
 * Speech recognition rarely returns names exactly as typed in the app, so matching is
 * word-based and tolerant: case, diacritics, emojis and punctuation are ignored,
 * plurals match singulars ("savings" ~ "Saving"), small misspellings match ("masr" ~ "Misr"),
 * spelled-out letters match acronyms ("c i b" ~ "CIB") and part of a name matches
 * ("vodafone" ~ "Vodafone Cash") unless another name matches as well or better.
 * Arabic is supported too, including English names said in Arabic - see [ArabicText].
 */
object NameMatcher {
    private val diacritics = Regex("\\p{Mn}+")
    private val tokenSeparator = Regex("[^\\p{L}\\p{N}]+")

    // Words that don't identify an account or a category on their own
    private val genericWords = setOf("account", "acc", "acct", "my", "the", "حساب", "حسابي")

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(diacritics, "")
        .lowercase(Locale.ROOT)
        .let(ArabicText::normalize)
        .trim()

    /**
     * Normalized words of [text] with letters spelled one by one merged ("c i b" -> "cib"),
     * simple plurals reduced to their singular and the Arabic article removed ("الكاش" -> "كاش").
     */
    fun tokens(text: String): List<String> {
        val words = normalize(text).split(tokenSeparator).filter { it.isNotEmpty() }
        val merged = mutableListOf<String>()
        var letters = ""
        for (word in words) {
            if (word.length == 1 && word[0].isLetter()) {
                letters += word
            } else {
                if (letters.isNotEmpty()) merged += letters
                letters = ""
                merged += word
            }
        }
        if (letters.isNotEmpty()) merged += letters
        return merged.map { singular(ArabicText.withoutArticle(it)) }
    }

    /**
     * Finds the item whose name best matches [name] (e.g. a name returned by an LLM).
     */
    fun <T> bestMatch(
        name: String,
        items: List<T>,
        itemName: (T) -> String,
    ): T? {
        val target = tokens(name)
        if (target.isEmpty()) return null
        return best(items.mapNotNull { item -> score(target, item, itemName)?.let { item to it } })
    }

    /**
     * Finds the items whose names are mentioned in [text], ordered by where they appear.
     * When names match the same words (e.g. "Cash" and "Vodafone Cash" for "vodafone cash"),
     * only the best match is kept, or none if they match equally well.
     */
    fun <T> mentioned(
        text: String,
        items: List<T>,
        itemName: (T) -> String,
    ): List<T> {
        val textTokens = tokens(text)
        val scored = items.mapNotNull { item -> score(textTokens, item, itemName)?.let { item to it } }
        val accepted = mutableListOf<Pair<T, MatchScore>>()
        val usedPositions = mutableSetOf<Int>()
        scored.sortedWith(compareByDescending(scoreComparator) { it.second }).forEach { candidate ->
            val (item, score) = candidate
            if (score.positions.none { it in usedPositions }) {
                val ambiguous = scored.any { (other, otherScore) ->
                    other != item &&
                        otherScore.positions.any { it in score.positions } &&
                        scoreComparator.compare(otherScore, score) == 0
                }
                if (!ambiguous) accepted += candidate
                usedPositions += score.positions
            }
        }
        return accepted.sortedBy { (_, score) -> score.positions.min() }.map { it.first }
    }

    /**
     * Whether [word] is one of the words of [name].
     */
    fun isPartOfName(word: String, name: String): Boolean {
        val token = tokens(word).singleOrNull() ?: return false
        return significantTokens(name).any { similar(token, it) }
    }

    private fun <T> score(
        textTokens: List<String>,
        item: T,
        itemName: (T) -> String,
    ): MatchScore? {
        val nameTokens = significantTokens(itemName(item))
        val positions = nameTokens.map { nameToken ->
            textTokens.indexOfFirst { similar(it, nameToken) }
        }
        val matched = positions.count { it >= 0 }
        // Part of a name is enough ("Vodafone" for "Vodafone Cash"), better matches win
        return if (matched == 0) {
            null
        } else {
            MatchScore(
                positions = positions.filter { it >= 0 }.toSet(),
                matchedRatio = matched.toDouble() / nameTokens.size,
                matched = matched,
                exact = nameTokens.count { it in textTokens },
            )
        }
    }

    /**
     * @return the best scored item or null if the best score is shared by different items
     * (e.g. "bank" when there are "Bank Misr" and "Bank Alahly").
     */
    private fun <T> best(scored: List<Pair<T, MatchScore>>): T? {
        val sorted = scored.sortedWith(compareByDescending(scoreComparator) { it.second })
        val first = sorted.firstOrNull() ?: return null
        val second = sorted.getOrNull(1)
        val ambiguous = second != null && scoreComparator.compare(first.second, second.second) == 0
        return if (ambiguous) null else first.first
    }

    private fun significantTokens(name: String): List<String> {
        val all = tokens(name)
        return all.filterNot { it in genericWords }.ifEmpty { all }
    }

    private fun singular(word: String): String = when {
        word.length <= MIN_PLURAL_LENGTH -> word
        word.endsWith("ies") -> word.removeSuffix("ies") + "y"
        word.endsWith("s") && !word.endsWith("ss") -> word.removeSuffix("s")
        else -> word
    }

    private fun similar(a: String, b: String): Boolean {
        val aArabic = ArabicText.isArabic(a)
        val bArabic = ArabicText.isArabic(b)
        return when {
            aArabic && bArabic -> ArabicText.variants(a).any { x ->
                ArabicText.variants(b).any { y -> similarSpelling(x, y) }
            }

            aArabic -> ArabicText.variants(a).any { ArabicText.soundsLike(it, b) }
            bArabic -> ArabicText.variants(b).any { ArabicText.soundsLike(it, a) }
            else -> similarSpelling(a, b)
        }
    }

    private fun similarSpelling(a: String, b: String): Boolean {
        val shorter = minOf(a.length, b.length)
        val allowedEdits = when {
            shorter >= LONG_WORD -> 2
            shorter >= MEDIUM_WORD -> 1
            else -> 0
        }
        // Numbers (e.g. card digits) must match exactly
        val hasDigits = a.any { it.isDigit() } || b.any { it.isDigit() }
        return a == b || (allowedEdits > 0 && !hasDigits && editDistance(a, b) <= allowedEdits)
    }

    private data class MatchScore(
        /** Indexes of the matched words in the text. */
        val positions: Set<Int>,
        val matchedRatio: Double,
        val matched: Int,
        val exact: Int,
    )

    private val scoreComparator: Comparator<MatchScore> = compareBy(
        { it.matchedRatio },
        { it.matched },
        { it.exact },
    )

    // Short words aren't fuzzy-matched to avoid false matches like "good" ~ "Food"
    private const val MIN_PLURAL_LENGTH = 3
    private const val MEDIUM_WORD = 5
    private const val LONG_WORD = 8
}
