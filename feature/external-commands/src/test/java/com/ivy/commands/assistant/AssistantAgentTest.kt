package com.ivy.commands.assistant

import arrow.core.Either
import com.ivy.base.time.TimeProvider
import com.ivy.commands.command.CommandArgs
import com.ivy.commands.command.CommandProcessor
import com.ivy.commands.command.account
import com.ivy.commands.command.category
import com.ivy.data.model.TransactionId
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.data.repository.CurrencyRepository
import com.ivy.data.repository.TagRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class AssistantAgentTest {
    private val gemini = mockk<GeminiClient>()
    private val processor = mockk<CommandProcessor>()
    private val accountRepository = mockk<AccountRepository>()
    private val categoryRepository = mockk<CategoryRepository>()
    private val tagRepository = mockk<TagRepository>()
    private val currencyRepository = mockk<CurrencyRepository>()
    private val timeProvider = mockk<TimeProvider>()
    private val config = AssistantConfig(apiKey = "key", model = "gemini-flash-latest")
    private val requests = mutableListOf<List<JsonObject>>()
    private val systems = slot<String>()

    private val agent = AssistantAgent(
        gemini = gemini,
        processor = processor,
        accountRepository = accountRepository,
        categoryRepository = categoryRepository,
        tagRepository = tagRepository,
        currencyRepository = currencyRepository,
        timeProvider = timeProvider,
    )

    private val saved = buildJsonObject {
        put("id", "t1")
        put("type", "expense")
        put("amount", 25.0)
        put("currency", "EGP")
        put("account", "Cash")
    }

    @Before
    fun setup() {
        coEvery { accountRepository.findAll() } returns listOf(account("Cash", "EGP"))
        coEvery { categoryRepository.findAll() } returns listOf(category("Food"))
        coEvery { tagRepository.findAll() } returns emptyList()
        coEvery { currencyRepository.getBaseCurrency() } returns AssetCode.unsafe("EGP")
        every { timeProvider.localDateNow() } returns LocalDate.of(2026, 11, 1)
    }

    private fun replies(vararg replies: Either<String, JsonObject>) {
        coEvery { gemini.generate(config, capture(systems), any(), any()) } answers {
            requests += thirdArg<List<JsonObject>>().toList()
            replies[requests.size - 1]
        }
    }

    private fun model(vararg parts: String): Either<String, JsonObject> = Either.Right(
        Json.parseToJsonElement("""{"role": "model", "parts": [${parts.joinToString()}]}""").jsonObject
    )

    @Test
    fun `runs the function calls and returns the reply`() = runTest {
        replies(
            model(
                """{"functionCall": {"id": "c1", "name": "add_transaction",
                "args": {"amount": 25, "account": "Cash", "category": "Food"}}, "thoughtSignature": "sig"}"""
            ),
            model("""{"text": "Saved 25 EGP for food."}"""),
        )
        val args = slot<CommandArgs>()
        coEvery { processor.execute("add_transaction", capture(args)) } returns Either.Right(saved)
        val actions = mutableListOf<AssistantAction>()

        val reply = agent.send("spent 25 on food", config) { actions += it }

        reply shouldBe Either.Right("Saved 25 EGP for food.")
        args.captured.double("amount") shouldBe 25.0
        args.captured.string("category") shouldBe "Food"
        actions shouldBe listOf(AssistantAction.Added(saved))
        systems.captured shouldContain "Today is Sunday, 2026-11-01."
        systems.captured shouldContain "Accounts: Cash (EGP)."
        systems.captured shouldContain "Categories: Food."

        // The model's content goes back as is (with its thought signature), then the results
        val second = requests[1]
        second.size shouldBe 3
        second[1]["parts"]!!.jsonArray[0].jsonObject["thoughtSignature"] shouldBe JsonPrimitive("sig")
        val response = second[2]["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
        second[2]["role"] shouldBe JsonPrimitive("user")
        response["id"] shouldBe JsonPrimitive("c1")
        response["name"] shouldBe JsonPrimitive("add_transaction")
        response["response"]!!.jsonObject["result"] shouldBe saved
    }

    @Test
    fun `sends command errors back to the model`() = runTest {
        replies(
            model("""{"functionCall": {"name": "add_transaction", "args": {"amount": 5, "account": "Wallet"}}}"""),
            model("""{"text": "Which account?"}"""),
        )
        coEvery { processor.execute(any(), any()) } returns Either.Left("Unknown account 'Wallet'.")

        val actions = mutableListOf<AssistantAction>()
        agent.send("spent 5 from wallet", config) { actions += it } shouldBe Either.Right("Which account?")

        actions shouldBe emptyList()
        requests[1][2]["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject["response"]!!
            .jsonObject["error"]!!.jsonPrimitive.content shouldBe "Unknown account 'Wallet'."
    }

    @Test
    fun `keeps the conversation and notes undone transactions`() = runTest {
        replies(model("""{"text": "Hi"}"""), model("""{"text": "OK"}"""))

        agent.send("hello", config) {}
        agent.noteUndo(TransactionId(UUID.fromString("5e6f3b52-7c0d-4f7e-9a3b-0f1e2d3c4b5a")))
        agent.send("thanks", config) {}

        val second = requests[1]
        second.size shouldBe 3
        second[2]["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content shouldBe
            "[The user undid (deleted) the transaction with id 5e6f3b52-7c0d-4f7e-9a3b-0f1e2d3c4b5a.]\nthanks"
    }

    @Test
    fun `an error leaves the conversation as it was`() = runTest {
        replies(Either.Left("Gemini: API key not valid."), model("""{"text": "Hi"}"""))

        agent.send("hello", config) {} shouldBe Either.Left("Gemini: API key not valid.")
        agent.send("hello again", config) {} shouldBe Either.Right("Hi")

        requests[1].size shouldBe 1
        coVerify(exactly = 0) { processor.execute(any(), any()) }
    }

    @Test
    fun `new chat`() = runTest {
        replies(model("""{"text": "Hi"}"""), model("""{"text": "Hi again"}"""))

        agent.send("hello", config) {}
        agent.reset()
        agent.send("hello", config) {}

        requests[1].size shouldBe 1
    }
}
