package com.ivy.voicecommands.parser

import arrow.core.Either
import com.ivy.base.model.TransactionType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import java.time.LocalDate

class ArabicVoiceCommandsTest {
    private val cash = account("Cash")
    private val bankMisr = account("Bank Misr")
    private val vodafoneCash = account("Vodafone Cash")
    private val visa = account("Visa")
    private val savings = account("التوفير")
    private val food = category("أكل")
    private val coffee = category("Coffee")

    private val parser = LocalVoiceTransactionParser()

    private fun parse(text: String): Either<VoiceParseError, VoiceTransactionDraft> = parser.parse(
        VoiceParserInput(
            text = text,
            categories = listOf(food, coffee),
            accounts = listOf(cash, bankMisr, vodafoneCash, visa, savings),
            today = Today,
        )
    )

    private fun parseDraft(text: String): VoiceTransactionDraft =
        parse(text).shouldBeInstanceOf<Either.Right<VoiceTransactionDraft>>().value

    @Test
    fun `expense with Arabic-Indic digits, category and yesterday`() {
        val draft = parseDraft("صرفت ٥٠ جنيه على الأكل امبارح")

        draft.type shouldBe TransactionType.EXPENSE
        draft.amount.value shouldBe 50.0
        draft.category shouldBe food.id
        draft.date shouldBe Today.minusDays(1)
        draft.title?.value shouldBe "الأكل"
    }

    @Test
    fun `transfer between English-named accounts said in Arabic`() {
        val draft = parseDraft("حولت 500 من الكاش لبنك مصر")

        draft.type shouldBe TransactionType.TRANSFER
        draft.amount.value shouldBe 500.0
        draft.account shouldBe cash.id
        draft.toAccount shouldBe bankMisr.id
        draft.title shouldBe null
    }

    @Test
    fun `transfer into an Arabic-named account`() {
        val draft = parseDraft("حطيت 2000 في التوفير")

        draft.type shouldBe TransactionType.TRANSFER
        draft.toAccount shouldBe savings.id
    }

    @Test
    fun `income to an account`() {
        val draft = parseDraft("استلمت المرتب 10000 على بنك مصر")

        draft.type shouldBe TransactionType.INCOME
        draft.amount.value shouldBe 10000.0
        draft.account shouldBe bankMisr.id
        draft.title?.value shouldBe "المرتب"
    }

    @Test
    fun `paid by card two days ago`() {
        val draft = parseDraft("دفعت 120 بالفيزا من يومين")

        draft.type shouldBe TransactionType.EXPENSE
        draft.amount.value shouldBe 120.0
        draft.account shouldBe visa.id
        draft.date shouldBe Today.minusDays(2)
        draft.title shouldBe null
    }

    @Test
    fun `full name wins over a partial one`() {
        parseDraft("صرفت 50 على الأكل من فودافون كاش").account shouldBe vodafoneCash.id
    }

    @Test
    fun `Arabic decimal separator`() {
        parseDraft("صرفت ٢٥٫٥ على القهوة").amount.value shouldBe 25.5
    }

    @Test
    fun `English category said in Arabic`() {
        parseDraft("اشتريت كوفي ب 40").category shouldBe coffee.id
    }

    @Test
    fun `Arabic weekday`() {
        parseDraft("صرفت 30 يوم الخميس").date shouldBe LocalDate.of(2026, 10, 15)
    }

    @Test
    fun `days ago`() {
        val draft = parseDraft("صرفت 75 من 3 ايام")

        draft.amount.value shouldBe 75.0
        draft.date shouldBe Today.minusDays(3)
    }

    @Test
    fun `similar sounding word with other vowels isn't an account`() {
        parseDraft("اشتريت كيس ب 5").account shouldBe null
    }

    @Test
    fun `and-prefixed verb`() {
        parseDraft("وحولت 100 من الكاش للتوفير").let {
            it.type shouldBe TransactionType.TRANSFER
            it.account shouldBe cash.id
            it.toAccount shouldBe savings.id
        }
    }

    @Test
    fun `no amount`() {
        parse("صرفت على الأكل") shouldBe Either.Left(VoiceParseError.NoAmount)
    }
}
