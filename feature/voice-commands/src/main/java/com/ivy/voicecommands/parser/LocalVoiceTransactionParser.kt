package com.ivy.voicecommands.parser

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
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
 * Offline, rule-based parser for English voice commands like
 * "spent 25 on coffee", "received salary 3000" or "transfer 100 from Cash to Bank".
 * Used when no AI provider is configured or when the AI request fails.
 */
class LocalVoiceTransactionParser @Inject constructor() {

    fun parse(input: VoiceParserInput): Either<VoiceParseError, VoiceTransactionDraft> = either {
        ensure(input.text.isNotBlank()) { VoiceParseError.Blank }
        val text = NameMatcher.normalize(input.text)
        val words = words(text)

        val amount = ensureNotNull(
            amountRegex.find(text.replace(daysAgoRegex, " "))
                ?.let { parseAmount(it.value) }
                ?.let { PositiveDouble.from(it).getOrNull() }
        ) { VoiceParseError.NoAmount }

        val transferAccounts = transferAccounts(text, input.accounts)
        val type = when {
            transferAccounts != null || words.any { it in transferWords } -> TransactionType.TRANSFER
            words.any { it in incomeWords } || incomePhrases.any { text.contains(it) } ->
                TransactionType.INCOME

            else -> TransactionType.EXPENSE
        }

        val mentionedAccounts = NameMatcher.mentioned(text, input.accounts) { it.name.value }
        val (account, toAccount) = when (type) {
            TransactionType.TRANSFER ->
                transferAccounts ?: (mentionedAccounts.getOrNull(0) to mentionedAccounts.getOrNull(1))

            else -> mentionedAccounts.firstOrNull() to null
        }

        val category = if (type == TransactionType.TRANSFER) {
            null
        } else {
            NameMatcher.mentioned(text, input.categories) { it.name.value }
                .maxByOrNull { it.name.value.length }
        }

        VoiceTransactionDraft(
            type = type,
            amount = amount,
            title = title(originalText = input.text, accounts = input.accounts),
            category = category?.id,
            account = account?.id,
            toAccount = toAccount?.id,
            date = date(text, input.today),
        )
    }

    /**
     * Handles "from X to Y" where both X and Y are accounts.
     */
    private fun transferAccounts(
        text: String,
        accounts: List<Account>,
    ): Pair<Account, Account>? {
        val match = fromToRegex.find(text)
        val from = match?.let { NameMatcher.mentioned(it.groupValues[1], accounts) { it.name.value } }
            ?.firstOrNull()
        val to = match?.let { NameMatcher.mentioned(it.groupValues[2], accounts) { it.name.value } }
            ?.firstOrNull { it.id != from?.id }
        return if (from != null && to != null) from to to else null
    }

    private fun date(text: String, today: LocalDate): LocalDate? {
        val words = words(text)
        daysAgoRegex.find(text)?.let { match ->
            return match.groupValues[1].toLongOrNull()?.let { today.minusDays(it) }
        }
        return when {
            text.contains("day before yesterday") -> today.minusDays(2)
            "yesterday" in words -> today.minusDays(1)
            "today" in words || "tonight" in words -> null
            else -> weekday(words)?.let { day ->
                if (day == today.dayOfWeek) today else today.with(TemporalAdjusters.previous(day))
            }
        }
    }

    private fun weekday(words: List<String>): DayOfWeek? = DayOfWeek.entries.firstOrNull { day ->
        day.name.lowercase(Locale.ROOT) in words
    }

    private fun title(
        originalText: String,
        accounts: List<Account>,
    ): NotBlankTrimmedString? {
        val accountWords = accounts.flatMap { words(NameMatcher.normalize(it.name.value)) }.toSet()
        val title = words(originalText)
            .filterNot { word ->
                val normalized = NameMatcher.normalize(word)
                normalized in titleStopWords ||
                    normalized in accountWords ||
                    word.any { it.isDigit() }
            }
            .joinToString(" ")
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
        return NotBlankTrimmedString.from(title).getOrNull()
    }

    private fun words(text: String): List<String> = text
        .split(wordSeparator)
        .filter { it.isNotBlank() }

    companion object {
        private val wordSeparator = Regex("[^\\p{L}\\p{N}]+")

        // "1,500.50" (thousands separator) or "25", "25.50", "25,50"
        private val amountRegex = Regex("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:[.,]\\d+)?")
        private val thousandsRegex = Regex("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?")
        private val fromToRegex = Regex("from (.+?) to (.+)")
        private val daysAgoRegex = Regex("(\\d+) days? ago")

        private val incomeWords = setOf(
            "received", "receive", "earned", "earn", "salary", "income", "refund",
            "refunded", "sold", "paycheck", "bonus",
        )
        private val incomePhrases = listOf("got paid")
        private val transferWords = setOf("transfer", "transferred", "transfered", "moved", "move")
        private val titleStopWords = setOf(
            // actions
            "spent", "spend", "paid", "pay", "bought", "buy", "purchased", "add", "expense",
            "received", "receive", "earned", "earn", "income", "got", "transfer",
            "transferred", "transfered", "moved", "move",
            // fillers
            "i", "a", "an", "the", "my", "for", "on", "at", "in", "of", "with", "from", "to",
            "and", "some", "today", "tonight", "yesterday", "day", "days", "ago", "before",
            "last", "this",
            // currency
            "dollar", "dollars", "usd", "euro", "euros", "eur", "pound", "pounds", "gbp",
            "buck", "bucks",
        ) + DayOfWeek.entries.map { it.name.lowercase(Locale.ROOT) }

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
