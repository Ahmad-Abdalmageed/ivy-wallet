package com.ivy.voicecommands.parser

import arrow.core.Either
import com.ivy.base.model.TransactionType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import java.time.LocalDate

class LocalVoiceTransactionParserTest {
    private val food = category("Food")
    private val fastFood = category("Fast Food")
    private val coffee = category("Coffee")
    private val groceries = category("Groceries")
    private val cafe = category("Café")
    private val cash = account("Cash")
    private val bank = account("Bank")

    private val parser = LocalVoiceTransactionParser()

    private fun parse(text: String): Either<VoiceParseError, VoiceTransactionDraft> = parser.parse(
        VoiceParserInput(
            text = text,
            categories = listOf(food, fastFood, coffee, groceries, cafe),
            accounts = listOf(cash, bank),
            today = Today,
        )
    )

    private fun parseDraft(text: String): VoiceTransactionDraft =
        parse(text).shouldBeInstanceOf<Either.Right<VoiceTransactionDraft>>().value

    @Test
    fun `simple expense`() {
        val draft = parseDraft("spent 25 on coffee")

        draft.type shouldBe TransactionType.EXPENSE
        draft.amount.value shouldBe 25.0
        draft.title?.value shouldBe "Coffee"
        draft.category shouldBe coffee.id
        draft.account shouldBe null
        draft.toAccount shouldBe null
        draft.date shouldBe null
    }

    @Test
    fun `expense with decimals, account and yesterday`() {
        val draft = parseDraft("Paid 12.50 for taxi yesterday with Cash")

        draft.type shouldBe TransactionType.EXPENSE
        draft.amount.value shouldBe 12.5
        draft.title?.value shouldBe "Taxi"
        draft.category shouldBe null
        draft.account shouldBe cash.id
        draft.date shouldBe Today.minusDays(1)
    }

    @Test
    fun `income`() {
        val draft = parseDraft("received salary 3000 to Bank")

        draft.type shouldBe TransactionType.INCOME
        draft.amount.value shouldBe 3000.0
        draft.title?.value shouldBe "Salary"
        draft.account shouldBe bank.id
    }

    @Test
    fun `got paid is income`() {
        val draft = parseDraft("got paid 500")

        draft.type shouldBe TransactionType.INCOME
        draft.title shouldBe null
    }

    @Test
    fun `transfer from account to account`() {
        val draft = parseDraft("transfer 100 from Cash to Bank")

        draft.type shouldBe TransactionType.TRANSFER
        draft.amount.value shouldBe 100.0
        draft.account shouldBe cash.id
        draft.toAccount shouldBe bank.id
        draft.category shouldBe null
    }

    @Test
    fun `thousands separator`() {
        val draft = parseDraft("moved 1,500.50 from bank to cash")

        draft.type shouldBe TransactionType.TRANSFER
        draft.amount.value shouldBe 1500.5
        draft.account shouldBe bank.id
        draft.toAccount shouldBe cash.id
    }

    @Test
    fun `decimal comma`() {
        val draft = parseDraft("25,50 groceries")

        draft.amount.value shouldBe 25.5
        draft.category shouldBe groceries.id
        draft.title?.value shouldBe "Groceries"
    }

    @Test
    fun `days ago doesn't affect the amount`() {
        val draft = parseDraft("3 days ago spent 20 on lunch")

        draft.amount.value shouldBe 20.0
        draft.date shouldBe Today.minusDays(3)
        draft.title?.value shouldBe "Lunch"
    }

    @Test
    fun `weekday is the last one`() {
        parseDraft("spent 10 on Monday").date shouldBe LocalDate.of(2026, 10, 12)
    }

    @Test
    fun `today's weekday is today`() {
        parseDraft("spent 10 on saturday").date shouldBe Today
    }

    @Test
    fun `category matching ignores diacritics`() {
        parseDraft("spent 4 at cafe").category shouldBe cafe.id
    }

    @Test
    fun `prefers the longest matching category`() {
        parseDraft("spent 9 on fast food").category shouldBe fastFood.id
    }

    @Test
    fun `transfer between accounts with names as spoken`() {
        val accounts = listOf(account("💵 Cash"), account("Bank Misr"), account("Saving"), account("CIB Credit Card"))
        fun parseWith(text: String): VoiceTransactionDraft = parser.parse(
            VoiceParserInput(text = text, categories = emptyList(), accounts = accounts, today = Today)
        ).shouldBeInstanceOf<Either.Right<VoiceTransactionDraft>>().value

        val transfer = parseWith("Transfer 500 from Bank Misr to my savings account")
        transfer.type shouldBe TransactionType.TRANSFER
        transfer.account shouldBe accounts[1].id
        transfer.toAccount shouldBe accounts[2].id
        transfer.title shouldBe null

        val acronym = parseWith("sent 200 from c i b card to cash")
        acronym.type shouldBe TransactionType.TRANSFER
        acronym.account shouldBe accounts[3].id
        acronym.toAccount shouldBe accounts[0].id
        acronym.title shouldBe null
    }

    @Test
    fun `transfer with only the receiving account`() {
        val draft = parseDraft("transfer 100 to bank")

        draft.type shouldBe TransactionType.TRANSFER
        draft.account shouldBe null
        draft.toAccount shouldBe bank.id
    }

    @Test
    fun `expense from an account and to a person`() {
        val draft = parseDraft("paid 50 from cash to Ahmed")

        draft.type shouldBe TransactionType.EXPENSE
        draft.account shouldBe cash.id
        draft.title?.value shouldBe "Ahmed"
    }

    @Test
    fun `income into an account`() {
        val draft = parseDraft("got paid 900 into bank")

        draft.type shouldBe TransactionType.INCOME
        draft.account shouldBe bank.id
    }

    @Test
    fun `no amount`() {
        parse("coffee") shouldBe Either.Left(VoiceParseError.NoAmount)
    }

    @Test
    fun `zero is not an amount`() {
        parse("spent 0 on coffee") shouldBe Either.Left(VoiceParseError.NoAmount)
    }

    @Test
    fun `blank text`() {
        parse("   ") shouldBe Either.Left(VoiceParseError.Blank)
    }
}
