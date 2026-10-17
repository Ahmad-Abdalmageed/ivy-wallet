package com.ivy.voicecommands.parser

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.ivy.base.model.TransactionType
import com.ivy.data.model.Account
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import javax.inject.Inject

/**
 * Offline, rule-based parser for English and Arabic voice commands like
 * "spent 25 on coffee", "received salary 3000", "transfer 100 from Cash to Bank",
 * "صرفت 50 على الأكل امبارح" or "حولت 500 من الكاش لبنك مصر".
 * Used when no AI provider is configured or when the AI request fails.
 */
class LocalVoiceTransactionParser @Inject constructor() {

    /**
     * @return one draft per transaction mentioned, e.g. "spent 50 on food and 20 on coffee"
     */
    fun parse(input: VoiceParserInput): Either<VoiceParseError, List<VoiceTransactionDraft>> = either {
        ensure(input.text.isNotBlank()) { VoiceParseError.Blank }
        var previous: VoiceTransactionDraft? = null
        val drafts = segments(input.text).mapNotNull { segment ->
            parseSegment(segment, input, previous)?.also { previous = it }
        }
        ensure(drafts.isNotEmpty()) { VoiceParseError.NoAmount }
        drafts
    }

    /**
     * Splits the text into one part per transaction: at "and", "then", "و", "ثم", commas, etc.
     * Parts without an amount belong to the previous transaction ("on food and drinks").
     */
    private fun segments(text: String): List<String> {
        val pieces = mutableListOf<String>()
        val separators = mutableListOf<String>()
        var start = 0
        separatorRegex.findAll(text).forEach { match ->
            pieces += text.substring(start, match.range.first)
            separators += match.value
            start = match.range.last + 1
        }
        pieces += text.substring(start)

        val segments = mutableListOf<String>()
        var leading = ""
        pieces.forEachIndexed { index, piece ->
            when {
                hasAmount(piece) -> {
                    segments += leading + piece
                    leading = ""
                }

                segments.isEmpty() -> leading += piece + separators.getOrElse(index) { "" }
                else -> segments[segments.lastIndex] = segments.last() + separators[index - 1] + piece
            }
        }
        return segments
    }

    private fun hasAmount(text: String): Boolean =
        amountRegex.containsMatchIn(NameMatcher.normalize(text).replace(daysAgoRegex, " "))

    /**
     * @param previous the transaction said before this one - its type, date and account
     * apply when this one doesn't mention them ("spent 50 on food and 20 on coffee yesterday").
     */
    private fun parseSegment(
        segment: String,
        input: VoiceParserInput,
        previous: VoiceTransactionDraft?,
    ): VoiceTransactionDraft? {
        val text = NameMatcher.normalize(segment)
        val keys = NameMatcher.tokens(text)
        val amount = amountRegex.find(text.replace(daysAgoRegex, " "))
            ?.let { parseAmount(it.value) }
            ?.let { PositiveDouble.from(it).getOrNull() }
            ?: return null

        val roles = accountRoles(text, input.accounts)
        val type = type(text, keys, roles) ?: previous?.type ?: TransactionType.EXPENSE
        val (account, toAccount) = accounts(type, roles, text, input.accounts)
        val sameTypeAsPrevious = previous?.type == type

        val category = if (type == TransactionType.TRANSFER) {
            null
        } else {
            NameMatcher.mentioned(text, input.categories) { it.name.value }
                .maxByOrNull { it.name.value.length }
        }

        return VoiceTransactionDraft(
            type = type,
            amount = amount,
            title = title(originalText = segment, accounts = input.accounts),
            category = category?.id,
            account = account?.id ?: previous?.account?.takeIf { sameTypeAsPrevious },
            toAccount = toAccount?.id ?: previous?.toAccount?.takeIf { sameTypeAsPrevious },
            date = date(text, input.today) ?: previous?.date,
        )
    }

    /**
     * @return null when the text doesn't say what kind of transaction it is ("and 20 on coffee")
     */
    private fun type(text: String, keys: List<String>, roles: AccountRoles): TransactionType? = when {
        (roles.from != null && roles.to != null) || keys.anyIn(transferWords) -> TransactionType.TRANSFER
        keys.anyIn(incomeWords) || incomePhrases.any { text.contains(it) } -> TransactionType.INCOME
        keys.anyIn(expenseWords) -> TransactionType.EXPENSE
        else -> null
    }

    /**
     * @return the paying (or receiving for income) account and, for transfers, the receiving one
     */
    private fun accounts(
        type: TransactionType,
        roles: AccountRoles,
        text: String,
        accounts: List<Account>,
    ): Pair<Account?, Account?> {
        val mentioned = NameMatcher.mentioned(text, accounts) { it.name.value }
        return when (type) {
            TransactionType.TRANSFER -> {
                val from = roles.from ?: mentioned.firstOrNull { it.id != roles.to?.id }
                val to = roles.to ?: mentioned.firstOrNull { it.id != from?.id }
                from to to
            }

            TransactionType.INCOME -> (roles.to ?: roles.from ?: mentioned.firstOrNull()) to null
            TransactionType.EXPENSE -> (roles.from ?: mentioned.firstOrNull()) to null
        }
    }

    private data class AccountRoles(
        val from: Account?,
        val to: Account?,
    )

    /**
     * Finds the accounts mentioned after "from ..." and "to ..." (e.g. "from Cash to Bank",
     * "من الكاش الى التوفير").
     */
    private fun accountRoles(
        text: String,
        accounts: List<Account>,
    ): AccountRoles {
        val from = fromRegex.find(text)?.let { match ->
            NameMatcher.mentioned(match.groupValues[1], accounts) { it.name.value }.firstOrNull()
        }
        val to = toRegex.find(text)?.let { match ->
            NameMatcher.mentioned(match.groupValues[1], accounts) { it.name.value }
                .firstOrNull { it.id != from?.id }
        }
        return AccountRoles(from = from, to = to)
    }

    private fun date(text: String, today: LocalDate): LocalDate? {
        val words = words(text)
        val daysAgo = daysAgoRegex.find(text)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toLongOrNull()
            ?: if (twoDaysAgoRegex.containsMatchIn(text)) 2L else null
        return when {
            daysAgo != null -> today.minusDays(daysAgo)
            dayBeforeYesterdayPhrases.any { text.contains(it) } -> today.minusDays(2)
            words.any { it in yesterdayWords } -> today.minusDays(1)
            words.any { it in todayWords } -> today
            else -> words.firstNotNullOfOrNull { weekdays[it] }?.let { day ->
                if (day == today.dayOfWeek) today else today.with(TemporalAdjusters.previous(day))
            }
        }
    }

    private fun title(
        originalText: String,
        accounts: List<Account>,
    ): NotBlankTrimmedString? {
        val title = words(originalText)
            .filterNot { word ->
                NameMatcher.tokens(word).anyIn(titleStopWords) ||
                    // amounts and spelled-out letters ("c i b")
                    word.length == 1 ||
                    word.any { it.isDigit() } ||
                    accounts.any { NameMatcher.isPartOfName(word, it.name.value) }
            }
            .joinToString(" ")
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
        return NotBlankTrimmedString.from(title).getOrNull()
    }

    private fun words(text: String): List<String> = text
        .split(wordSeparator)
        .filter { it.isNotBlank() }

    /**
     * Whether any of the words, or an Arabic word without its attached prefix ("وصرفت"), is in [set].
     */
    private fun List<String>.anyIn(set: Set<String>): Boolean = any { word ->
        ArabicText.variants(word).any { it in set }
    }

    companion object {
        private val wordSeparator = Regex("[^\\p{L}\\p{N}]+")

        // "and", "then", commas... and Arabic "و" attached to a verb ("وصرفت")
        private val separatorRegex = Regex(
            "\\s*[,;،؛]\\s+|\\.\\s+" +
                "|\\s+(?:and then|and also|and|then|also|plus|ثم|وبعدين|وكمان|كمان|و)\\s+" +
                "|\\s+(?=و(?:صرفت|دفعت|حولت|اشتريت|شريت|استلمت|قبضت|حطيت|نقلت|جبت|سددت)(?![\\p{L}]))",
            RegexOption.IGNORE_CASE,
        )

        // "1,500.50" (thousands separator) or "25", "25.50", "25,50"
        private val amountRegex = Regex("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:[.,]\\d+)?")
        private val thousandsRegex = Regex("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?")

        // Arabic words are normalized: "إلى" -> "الي", "على" -> "علي"
        private val fromRegex = Regex(
            "(?<![\\p{L}\\p{N}])(?:from|out of|using|with|من) (.+?)" +
                "(?= (?:to|into|الي|علي|في|لحساب) |$)"
        )
        private val toRegex = Regex(
            "(?<![\\p{L}\\p{N}])(?:to|into|الي|علي|في|لحساب) (.+?)(?= (?:from|out of|من) |$)"
        )
        private val daysAgoRegex = Regex("(\\d+) days? ago|(?:من|قبل) (\\d+) (?:يوم|ايام)")
        private val twoDaysAgoRegex = Regex("(?:من|قبل) يومين")

        /** Normalized like the spoken text, then split into words like [NameMatcher.tokens]. */
        private fun keysOf(vararg words: String): Set<String> =
            words.flatMap { NameMatcher.tokens(it) }.toSet()

        private fun normalized(vararg words: String): Set<String> =
            words.map { NameMatcher.normalize(it) }.toSet()

        private val incomeWords = keysOf(
            "received", "receive", "earned", "earn", "salary", "income", "refund",
            "refunded", "sold", "paycheck", "bonus",
            "استلمت", "استلام", "قبضت", "قبض", "مرتب", "راتب", "دخل", "كسبت", "ربحت",
            "استرجعت", "استرداد", "جالي", "جاني", "وصلني", "بعتلي", "حولي", "اتحولي", "مكافأة",
        )
        private val incomePhrases = listOf("got paid")
        private val expenseWords = keysOf(
            "spent", "spend", "paid", "pay", "bought", "buy", "purchased",
            "صرفت", "صرف", "دفعت", "دفع", "اشتريت", "اشترى", "شريت", "شراء", "جبت", "سددت",
        )
        private val transferWords = keysOf(
            "transfer", "transferred", "transfered", "moved", "move",
            "حولت", "حول", "حولنا", "تحويل", "نقلت", "نقل", "حطيت",
        )
        private val yesterdayWords = normalized(
            "yesterday",
            "امبارح",
            "إمبارح",
            "أمس",
            "البارحة",
        )
        private val todayWords = normalized(
            "today",
            "tonight",
            "النهارده",
            "النهاردة",
            "انهارده",
            "اليوم",
        )
        private val dayBeforeYesterdayPhrases = normalized(
            "day before yesterday",
            "أول امبارح",
            "أول إمبارح",
            "أول أمس",
        )
        private val weekdays: Map<String, DayOfWeek> =
            DayOfWeek.entries.associateBy { it.name.lowercase(Locale.ROOT) } + mapOf(
                "السبت" to DayOfWeek.SATURDAY,
                "الأحد" to DayOfWeek.SUNDAY,
                "الحد" to DayOfWeek.SUNDAY,
                "الاثنين" to DayOfWeek.MONDAY,
                "الاتنين" to DayOfWeek.MONDAY,
                "الثلاثاء" to DayOfWeek.TUESDAY,
                "التلات" to DayOfWeek.TUESDAY,
                "الأربعاء" to DayOfWeek.WEDNESDAY,
                "الأربع" to DayOfWeek.WEDNESDAY,
                "الخميس" to DayOfWeek.THURSDAY,
                "الجمعة" to DayOfWeek.FRIDAY,
            ).mapKeys { (word, _) -> NameMatcher.normalize(word) }
        private val titleStopWords = keysOf(
            // actions
            "spent", "spend", "paid", "pay", "bought", "buy", "purchased", "add", "expense",
            "received", "receive", "earned", "earn", "income", "got", "transfer",
            "transferred", "transfered", "moved", "move", "sent", "send", "into", "out",
            "using", "via", "by", "account", "accounts",
            "صرفت", "صرف", "دفعت", "دفع", "اشتريت", "اشترى", "شريت", "شراء", "جبت", "سددت",
            "مصاريف", "مصروف", "استلمت", "قبضت", "كسبت", "جالي", "وصلني", "حولت", "حول",
            "تحويل", "نقلت", "حطيت", "حساب", "حسابي",
            // fillers
            "i", "a", "an", "the", "my", "for", "on", "at", "in", "of", "with", "from", "to",
            "and", "some", "today", "tonight", "yesterday", "day", "days", "ago", "before",
            "last", "this", "then", "also", "plus",
            "انا", "من", "إلى", "على", "في", "لحساب", "عشان", "علشان", "بتاع", "بتاعي", "ده",
            "دي", "قبل", "أول", "يوم", "أيام", "يومين", "امبارح", "أمس", "البارحة",
            "النهارده", "النهاردة", "اليوم", "و", "ثم", "وبعدين", "كمان", "وكمان",
            // currency
            "dollar", "dollars", "usd", "euro", "euros", "eur", "pound", "pounds", "gbp",
            "buck", "bucks",
            "جنيه", "جنيهات", "جنيها", "دولار", "دولارات", "يورو", "ريال", "ريالات", "درهم",
            "دراهم", "دينار", "ليرة", "مصري",
        ) + weekdays.keys.flatMap { NameMatcher.tokens(it) }

        fun parseAmount(token: String): Double? {
            val normalized = if (thousandsRegex.matches(token)) {
                token.replace(",", "")
            } else {
                token.replace(',', '.')
            }
            return normalized.toDoubleOrNull()?.takeIf { it > 0 }
        }
    }
}
