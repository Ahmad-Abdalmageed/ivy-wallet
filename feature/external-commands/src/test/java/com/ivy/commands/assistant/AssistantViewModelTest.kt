package com.ivy.commands.assistant

import arrow.core.Either
import com.ivy.commands.command.CommandProcessor
import com.ivy.data.model.TransactionId
import com.ivy.navigation.Navigation
import com.ivy.ui.testing.ComposeViewModelTest
import com.ivy.ui.testing.runTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Test
import java.util.UUID

class AssistantViewModelTest : ComposeViewModelTest() {
    private val agent = mockk<AssistantAgent>(relaxUnitFun = true)
    private val settings = mockk<AssistantSettings>(relaxUnitFun = true)
    private val processor = mockk<CommandProcessor>()
    private val config = AssistantConfig(apiKey = "key", model = "gemini-flash-latest")
    private lateinit var viewModel: AssistantViewModel
    private val transactionId = TransactionId(UUID.fromString("5e6f3b52-7c0d-4f7e-9a3b-0f1e2d3c4b5a"))

    private val added = buildJsonObject {
        put("id", transactionId.value.toString())
        put("type", "expense")
        put("amount", 25.0)
        put("currency", "EGP")
        put("account", "Cash")
        put("category", "Food")
        put("title", "Lunch")
        put("date", "2026-11-01T13:00")
        put("tags", buildJsonArray { add(JsonPrimitive("work")) })
    }

    @Before
    fun setup() {
        coEvery { settings.config() } returns config
        coEvery { settings.model() } returns ""
        coEvery { agent.send("lunch 25", config, any()) } coAnswers {
            thirdArg<suspend (AssistantAction) -> Unit>().invoke(AssistantAction.Added(added))
            Either.Right("Saved.")
        }
        viewModel = AssistantViewModel(agent, settings, processor, Navigation())
    }

    @Test
    fun `sends a message and shows what was saved`() {
        viewModel.runTest(
            events = listOf(
                AssistantEvent.OnInputChange("lunch 25"),
                AssistantEvent.OnSendClick,
            )
        ) {
            input shouldBe ""
            sending shouldBe false
            messages.size shouldBe 3
            messages[0] shouldBe ChatMessage.User(0, "lunch 25")
            val card = messages[1]
            card.shouldBeInstanceOf<ChatMessage.Added>()
            card.transactionId shouldBe transactionId
            card.amount shouldBe "25.0 EGP"
            card.details shouldBe "Cash · Food · Lunch · 2026-11-01T13:00 · #work"
            messages[2] shouldBe ChatMessage.Assistant(2, "Saved.")
        }
    }

    @Test
    fun `undo deletes the transaction and tells the assistant`() {
        coEvery { processor.execute("delete_transaction", any()) } returns
            Either.Right(buildJsonObject { put("deleted", transactionId.value.toString()) })

        viewModel.runTest(events = listOf(AssistantEvent.OnSpeechResult("lunch 25"))) {
            messages[1].shouldBeInstanceOf<ChatMessage.Added>()
        }
        viewModel.runTest(
            events = listOf(AssistantEvent.OnUndoClick(addedMessage()))
        ) {
            (messages[1] as ChatMessage.Added).undone shouldBe true
        }
        coVerify { agent.noteUndo(transactionId) }
    }

    @Test
    fun `shows the error`() {
        coEvery { agent.send(any(), any(), any()) } returns Either.Left("Gemini: API key not valid.")

        viewModel.runTest(events = listOf(AssistantEvent.OnExampleClick("hi"))) {
            messages.last() shouldBe ChatMessage.Error(1, "Gemini: API key not valid.")
        }
    }

    @Test
    fun `asks for the API key first`() {
        coEvery { settings.config() } returns null

        viewModel.runTest {
            hasApiKey shouldBe false
            this.settings?.hasSavedKey shouldBe false
        }
    }

    @Test
    fun `listens on start when a key is saved`() {
        viewModel.listenOnStart(true)

        viewModel.runTest {
            listenOnStart shouldBe true
        }
    }

    private fun addedMessage(): ChatMessage.Added = ChatMessage.Added(
        id = 1,
        transactionId = transactionId,
        type = "expense",
        amount = "25.0 EGP",
        details = "",
        undone = false,
    )
}
