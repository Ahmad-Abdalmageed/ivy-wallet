package com.ivy.voicecommands.parser

import arrow.core.Either
import com.ivy.base.model.TransactionType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

class MultipleTransactionsParserTest {
    private val food = category("Food")
    private val drinks = category("Food and Drinks")
    private val coffee = category("Coffee")
    private val cash = account("Cash")
    private val bank = account("Bank Misr")
    private val savings = account("Saving")

    private val parser = LocalVoiceTransactionParser()

    private fun parse(text: String): List<VoiceTransactionDraft> = parser.parse(
        VoiceParserInput(
            text = text,
            categories = listOf(food, drinks, coffee),
            accounts = listOf(cash, bank, savings),
            today = Today,
        )
    ).shouldBeInstanceOf<Either.Right<List<VoiceTransactionDraft>>>().value

    @Test
    fun `two expenses joined with and`() {
        val drafts = parse("spent 50 on food and 20 on coffee")

        drafts.size shouldBe 2
        drafts[0].amount.value shouldBe 50.0
        drafts[0].category shouldBe food.id
        drafts[1].amount.value shouldBe 20.0
        drafts[1].category shouldBe coffee.id
        drafts[1].type shouldBe TransactionType.EXPENSE
    }

    @Test
    fun `mixed types, accounts and dates`() {
        val drafts = parse(
            "yesterday I paid 120 for coffee with cash, then transferred 500 from Bank Misr to savings. " +
                "Also received 3000 salary to bank misr"
        )

        drafts.size shouldBe 3
        drafts[0].type shouldBe TransactionType.EXPENSE
        drafts[0].account shouldBe cash.id
        drafts[0].date shouldBe Today.minusDays(1)
        drafts[1].type shouldBe TransactionType.TRANSFER
        drafts[1].account shouldBe bank.id
        drafts[1].toAccount shouldBe savings.id
        drafts[2].type shouldBe TransactionType.INCOME
        drafts[2].account shouldBe bank.id
    }

    @Test
    fun `type, date and account carry over to the next transaction`() {
        val drafts = parse("spent 50 on food from cash yesterday and 20 on coffee")

        drafts[1].type shouldBe TransactionType.EXPENSE
        drafts[1].date shouldBe Today.minusDays(1)
        drafts[1].account shouldBe cash.id
    }

    @Test
    fun `today stops the date carry over`() {
        val drafts = parse("spent 50 on food yesterday and 20 on coffee today")

        drafts[0].date shouldBe Today.minusDays(1)
        drafts[1].date shouldBe Today
    }

    @Test
    fun `and inside a name doesn't split`() {
        val drafts = parse("spent 75 on food and drinks")

        drafts.size shouldBe 1
        drafts.single().category shouldBe drinks.id
    }

    @Test
    fun `numbers with separators don't split`() {
        val drafts = parse("moved 1,500.50 from bank misr to savings")

        drafts.single().amount.value shouldBe 1500.5
    }

    @Test
    fun `Arabic transactions joined with و and ثم`() {
        val drafts = parse("صرفت 50 على الأكل وحولت 200 من الكاش لبنك مصر ثم استلمت 1000 على التوفير")

        drafts.size shouldBe 3
        drafts[0].type shouldBe TransactionType.EXPENSE
        drafts[0].amount.value shouldBe 50.0
        drafts[1].type shouldBe TransactionType.TRANSFER
        drafts[1].account shouldBe cash.id
        drafts[1].toAccount shouldBe bank.id
        drafts[2].type shouldBe TransactionType.INCOME
        // "التوفير" is a translation of "Saving", not the way it sounds
        drafts[2].account shouldBe null
    }

    @Test
    fun `Arabic standalone و`() {
        val drafts = parse("صرفت 30 على القهوة و 45 على الأكل امبارح")

        drafts.size shouldBe 2
        drafts[1].amount.value shouldBe 45.0
        drafts[1].type shouldBe TransactionType.EXPENSE
        drafts[1].date shouldBe Today.minusDays(1)
    }

    @Test
    fun `leading words without an amount belong to the first transaction`() {
        val drafts = parse("yesterday, spent 10 on coffee")

        drafts.single().date shouldBe Today.minusDays(1)
    }
}
