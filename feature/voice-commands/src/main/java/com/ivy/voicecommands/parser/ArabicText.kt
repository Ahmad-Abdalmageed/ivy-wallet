package com.ivy.voicecommands.parser

/**
 * Arabic support for matching spoken text:
 * - spelling variants are unified ("أ" "إ" "آ" -> "ا", "ة" -> "ه", "ى" -> "ي")
 * - Arabic-Indic digits become 0-9
 * - the definite article and the prefixes attached to it are dropped ("بالكاش" -> "كاش")
 * - Arabic words are compared to Latin ones by their sounds, so English account names
 *   match when said in Arabic ("بنك مصر" ~ "Bank Misr", "كاش" ~ "Cash", "فودافون" ~ "Vodafone")
 */
object ArabicText {
    private const val TATWEEL = 'ـ'
    private val articlePrefixes = listOf("وبال", "وال", "بال", "فال", "كال", "لل", "ال")
    private val prefixLetters = setOf('و', 'ب', 'ل', 'ف', 'ك')
    private const val MIN_STEM_LENGTH = 2
    private const val MIN_SOUND_MATCH = 2
    private const val FUZZY_SOUND_MATCH = 4

    fun isArabic(char: Char): Boolean = char in '؀'..'ۿ' || char in 'ݐ'..'ݿ'

    fun isArabic(word: String): Boolean = word.any(::isArabic)

    /**
     * Expects text that is already lowercase and without diacritics (harakat and hamza marks).
     */
    fun normalize(text: String): String = buildString(text.length) {
        for (char in text) {
            when (char) {
                TATWEEL -> Unit
                'ة' -> append('ه')
                'ى' -> append('ي')
                '٫' -> append('.')
                '٬' -> append(',')
                in '٠'..'٩' -> append('0' + (char - '٠'))
                in '۰'..'۹' -> append('0' + (char - '۰'))
                else -> append(char)
            }
        }
    }

    fun withoutArticle(word: String): String {
        if (!isArabic(word)) return word
        val prefix = articlePrefixes.firstOrNull {
            word.startsWith(it) && word.length - it.length >= MIN_STEM_LENGTH
        }
        return prefix?.let(word::removePrefix) ?: word
    }

    /**
     * [word] and, when it starts with an attached preposition or "and" ("لبنك" -> "بنك"),
     * the word without it.
     */
    fun variants(word: String): List<String> {
        val withoutPrefix = word.drop(1)
        val hasPrefix = isArabic(word) &&
            word.first() in prefixLetters &&
            withoutPrefix.length > MIN_STEM_LENGTH
        return if (hasPrefix) listOf(word, withoutPrefix) else listOf(word)
    }

    /**
     * Whether the Arabic [arabic] word sounds like the Latin [latin] one.
     */
    fun soundsLike(arabic: String, latin: String): Boolean {
        if (latin.any { it.isDigit() }) return false
        val a = arabicSounds(arabic)
        val l = latinSounds(latin)
        val consonantsMatch = when {
            a.consonants.length < MIN_SOUND_MATCH -> false
            a.consonants == l.consonants -> true
            minOf(a.consonants.length, l.consonants.length) >= FUZZY_SOUND_MATCH ->
                editDistance(a.consonants, l.consonants) <= 1
            else -> false
        }
        // Few consonants are ambiguous ("كيس" vs "cash"), so long vowels must match too
        val vowelsMatch = a.consonants.length > MIN_SOUND_MATCH || a.vowels.isSubsequenceOf(l.vowels)
        return consonantsMatch && vowelsMatch
    }

    private data class Sounds(
        val consonants: String,
        val vowels: String,
    )

    private fun arabicSounds(word: String): Sounds {
        val consonants = StringBuilder()
        val vowels = StringBuilder()
        word.forEachIndexed { index, char ->
            val sound = arabicConsonants[char]
            when {
                sound != null -> consonants.append(sound)
                // A leading alef only carries the hamza, a leading waw/yaa is a consonant
                index == 0 -> Unit
                char == 'ا' -> vowels.append('a')
                char == 'و' -> vowels.append('u')
                char == 'ي' -> vowels.append('i')
                else -> Unit
            }
        }
        return Sounds(consonants.toString().collapseRepeats(), vowels.toString())
    }

    private fun latinSounds(word: String): Sounds {
        val simplified = word
            .replace("ph", "f")
            .replace("ck", "k")
            .replace("qu", "k")
            .replace("x", "ks")
            .replace(softC, "s")
        val consonants = StringBuilder()
        val vowels = StringBuilder()
        simplified.forEachIndexed { index, char ->
            when (char) {
                'a' -> vowels.append('a')
                'o', 'u' -> vowels.append('u')
                'e', 'i' -> vowels.append('i')
                'y' -> {
                    if (index == simplified.lastIndex) vowels.append('i')
                }
                'w', 'h' -> Unit
                else -> latinConsonants[char]?.let(consonants::append)
            }
        }
        return Sounds(consonants.toString().collapseRepeats(), vowels.toString())
    }

    private val softC = Regex("c(?=[eiy])")

    private val arabicConsonants: Map<Char, Char> = mapOf(
        'ب' to 'b', 'پ' to 'b',
        'ت' to 't', 'ط' to 't', 'ث' to 't',
        'د' to 'd', 'ض' to 'd', 'ذ' to 'd',
        'س' to 's', 'ش' to 's', 'ص' to 's', 'ز' to 's', 'ظ' to 's',
        'ج' to 'g', 'غ' to 'g', 'چ' to 'g', 'گ' to 'g',
        'ك' to 'k', 'ق' to 'k', 'خ' to 'k',
        'ف' to 'f', 'ڤ' to 'f',
        'ر' to 'r', 'ل' to 'l', 'م' to 'm', 'ن' to 'n',
    )

    private val latinConsonants: Map<Char, Char> = mapOf(
        'b' to 'b', 'p' to 'b',
        't' to 't', 'd' to 'd',
        's' to 's', 'z' to 's',
        'g' to 'g', 'j' to 'g',
        'k' to 'k', 'c' to 'k', 'q' to 'k',
        'f' to 'f', 'v' to 'f',
        'r' to 'r', 'l' to 'l', 'm' to 'm', 'n' to 'n',
    )

    private fun String.collapseRepeats(): String = buildString {
        for (char in this@collapseRepeats) {
            if (isEmpty() || last() != char) append(char)
        }
    }

    private fun String.isSubsequenceOf(other: String): Boolean {
        var index = 0
        for (char in other) {
            if (index < length && this[index] == char) index++
        }
        return index == length
    }
}
