package com.ivy.voicecommands.parser

import arrow.core.Either
import com.ivy.base.model.TransactionType
import com.ivy.base.time.TimeProvider
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.voicecommands.LlmConfig
import com.ivy.voicecommands.VoiceApiKeysRepository
import com.ivy.voicecommands.VoiceApiProvider
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class VoiceTransactionParserTest {
    private val apiKeysRepository = mockk<VoiceApiKeysRepository>()
    private val categoryRepository = mockk<CategoryRepository>()
    private val accountRepository = mockk<AccountRepository>()
    private val timeProvider = mockk<TimeProvider>()
    private val llmParser = mockk<LlmVoiceTransactionParser>()

    private val config = LlmConfig(
        provider = VoiceApiProvider.Groq,
        apiKey = NotBlankTrimmedString.unsafe("key"),
        model = NotBlankTrimmedString.unsafe("model"),
    )
    private val aiDraft = VoiceTransactionDraft(
        type = TransactionType.INCOME,
        amount = PositiveDouble.unsafe(42.0),
        title = null,
        category = null,
        account = null,
        toAccount = null,
        date = null,
    )

    private lateinit var parser: VoiceTransactionParser

    @Before
    fun setup() {
        coEvery { categoryRepository.findAll() } returns emptyList()
        coEvery { accountRepository.findAll() } returns emptyList()
        every { timeProvider.localDateNow() } returns Today
        parser = VoiceTransactionParser(
            apiKeysRepository = apiKeysRepository,
            categoryRepository = categoryRepository,
            accountRepository = accountRepository,
            timeProvider = timeProvider,
            llmParser = llmParser,
            localParser = LocalVoiceTransactionParser(),
        )
    }

    @Test
    fun `uses the offline parser without an AI provider`() = runTest {
        coEvery { apiKeysRepository.findActiveConfig() } returns Either.Right(null)

        val result = parser.parse("spent 25 on coffee").getOrNull()

        result?.draft?.amount?.value shouldBe 25.0
        result?.aiError shouldBe null
        coVerify(exactly = 0) { llmParser.parse(any(), any()) }
    }

    @Test
    fun `uses the AI provider when configured`() = runTest {
        coEvery { apiKeysRepository.findActiveConfig() } returns Either.Right(config)
        coEvery { llmParser.parse(config, any()) } returns Either.Right(aiDraft)

        parser.parse("got 42") shouldBe Either.Right(VoiceParseResult(draft = aiDraft, aiError = null))
    }

    @Test
    fun `falls back to the offline parser when the AI fails`() = runTest {
        coEvery { apiKeysRepository.findActiveConfig() } returns Either.Right(config)
        coEvery { llmParser.parse(config, any()) } returns Either.Left("Groq: Invalid API Key")

        val result = parser.parse("spent 25 on coffee").getOrNull()

        result?.draft?.amount?.value shouldBe 25.0
        result?.aiError shouldBe "Groq: Invalid API Key"
    }

    @Test
    fun `reports the AI error when the offline parser fails too`() = runTest {
        coEvery { apiKeysRepository.findActiveConfig() } returns Either.Right(config)
        coEvery { llmParser.parse(config, any()) } returns Either.Left("Groq: rate limited")

        parser.parse("قهوة بخمسة") shouldBe Either.Left(VoiceParseError.AiFailed("Groq: rate limited"))
    }

    @Test
    fun `blank text`() = runTest {
        parser.parse(" ") shouldBe Either.Left(VoiceParseError.Blank)
    }
}
