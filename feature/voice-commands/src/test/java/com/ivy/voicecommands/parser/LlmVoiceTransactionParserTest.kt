package com.ivy.voicecommands.parser

import arrow.core.Either
import com.ivy.base.model.TransactionType
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.voicecommands.LlmConfig
import com.ivy.voicecommands.VoiceApiProvider
import com.ivy.voicecommands.llm.LlmClient
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LlmVoiceTransactionParserTest {
    private val food = category("Food")
    private val cash = account("Cash")
    private val bank = account("Bank")
    private val input = VoiceParserInput(
        text = "اشتريت قهوة بـ 25 من الكاش امس",
        categories = listOf(food),
        accounts = listOf(cash, bank),
        today = Today,
    )
    private val config = LlmConfig(
        provider = VoiceApiProvider.Google,
        apiKey = NotBlankTrimmedString.unsafe("key"),
        model = NotBlankTrimmedString.unsafe("gemini-flash-latest"),
    )

    private val llmClient = mockk<LlmClient>()
    private val parser = LlmVoiceTransactionParser(llmClient)

    private fun dto(
        type: String? = "EXPENSE",
        amount: Double? = 25.0,
        title: String? = "قهوة",
        category: String? = "food",
        account: String? = "cash",
        toAccount: String? = null,
        date: String? = null,
    ): LlmTransactionDto = LlmTransactionDto(
        type = type,
        amount = amount,
        title = title,
        category = category,
        account = account,
        toAccount = toAccount,
        date = date,
    )

    private fun draft(dto: LlmTransactionDto): VoiceTransactionDraft =
        parser.toDraft(dto, input).shouldBeInstanceOf<Either.Right<VoiceTransactionDraft>>().value

    @Test
    fun `maps names to ids ignoring case`() {
        val draft = draft(dto(date = "2026-10-16"))

        draft.type shouldBe TransactionType.EXPENSE
        draft.amount.value shouldBe 25.0
        draft.title?.value shouldBe "قهوة"
        draft.category shouldBe food.id
        draft.account shouldBe cash.id
        draft.toAccount shouldBe null
        draft.date shouldBe Today.minusDays(1)
    }

    @Test
    fun `unknown names, type and future dates are ignored`() {
        val draft = draft(
            dto(type = "something", category = "Travel", account = "Revolut", date = "2026-12-01")
        )

        draft.type shouldBe TransactionType.EXPENSE
        draft.category shouldBe null
        draft.account shouldBe null
        draft.date shouldBe null
    }

    @Test
    fun `transfer keeps the receiving account and drops the category`() {
        val draft = draft(dto(type = "transfer", toAccount = "Bank"))

        draft.type shouldBe TransactionType.TRANSFER
        draft.account shouldBe cash.id
        draft.toAccount shouldBe bank.id
        draft.category shouldBe null
    }

    @Test
    fun `negative amounts become positive`() {
        draft(dto(type = "INCOME", amount = -50.0)).amount.value shouldBe 50.0
    }

    @Test
    fun `missing amount fails`() {
        parser.toDraft(dto(amount = null), input).shouldBeInstanceOf<Either.Left<String>>()
    }

    @Test
    fun `parses JSON wrapped in a markdown code block`() = runTest {
        coEvery { llmClient.complete(config, any()) } returns Either.Right(
            """
                ```json
                {"type": "EXPENSE", "amount": 25, "title": "Coffee", "category": "Food",
                 "account": "Cash", "toAccount": null, "date": null}
                ```
            """.trimIndent()
        )

        val draft = parser.parse(config, input)
            .shouldBeInstanceOf<Either.Right<VoiceTransactionDraft>>().value

        draft.amount.value shouldBe 25.0
        draft.category shouldBe food.id
    }

    @Test
    fun `invalid JSON fails`() = runTest {
        coEvery { llmClient.complete(config, any()) } returns Either.Right("I can't help with that")

        parser.parse(config, input).shouldBeInstanceOf<Either.Left<String>>()
    }

    @Test
    fun `prompt lists the categories and accounts`() {
        val prompt = parser.prompt(input)

        prompt.system shouldContain """Categories: ["Food"]"""
        prompt.system shouldContain """Accounts: ["Cash","Bank"]"""
        prompt.system shouldContain "Today is 2026-10-17 (Saturday)"
        prompt.user shouldBe input.text
    }
}
