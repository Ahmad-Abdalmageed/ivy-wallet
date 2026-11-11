package com.ivy.commands.assistant

import arrow.core.Either
import com.ivy.base.time.TimeProvider
import com.ivy.commands.command.CommandProcessor
import com.ivy.data.model.TransactionId
import com.ivy.navigation.Navigation
import com.ivy.ui.testing.ComposeViewModelTest
import com.ivy.ui.testing.runTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.collections.immutable.persistentListOf
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

class AssistantViewModelTest : ComposeViewModelTest() {
    private val agent = mockk<AssistantAgent>(relaxUnitFun = true)
    private val settings = mockk<AssistantSettings>(relaxUnitFun = true)
    private val processor = mockk<CommandProcessor>()
    private val receiptImages = mockk<ReceiptImages>()
    private val history = mockk<AssistantHistory>(relaxUnitFun = true)
    private val timeProvider = mockk<TimeProvider>()
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
        coEvery { agent.send("lunch 25", null, config, any()) } coAnswers {
            arg<suspend (AssistantAction) -> Unit>(3).invoke(AssistantAction.Added(added))
            Either.Right("Saved.")
        }
        every { timeProvider.utcNow() } returns Instant.ofEpochMilli(1_000)
        every { agent.snapshot() } returns AgentSnapshot(emptyList(), emptyList())
        coEvery { history.conversations() } returns emptyList()
        coEvery { history.savePhoto(any()) } returns null
        viewModel = AssistantViewModel(
            agent = agent,
            settings = settings,
            processor = processor,
            receiptImages = receiptImages,
            navigation = Navigation(),
            history = history,
            timeProvider = timeProvider,
        )
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
            messages[0] shouldBe ChatMessage.User(0, "lunch 25", null)
            val card = messages[1]
            card.shouldBeInstanceOf<ChatMessage.Added>()
            card.transaction shouldBe transactionId
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
        coEvery { agent.send(any(), any(), any(), any()) } returns Either.Left("Gemini: API key not valid.")

        viewModel.runTest(events = listOf(AssistantEvent.OnExampleClick("hi"))) {
            messages.last() shouldBe ChatMessage.Error(1, "Gemini: API key not valid.")
        }
    }

    @Test
    fun `sends a receipt photo`() {
        val jpeg = byteArrayOf(1, 2, 3)
        coEvery { receiptImages.jpeg("content://receipt") } returns jpeg
        coEvery { agent.send("", jpeg, config, any()) } returns Either.Right("Saved 85 EGP.")

        viewModel.runTest(
            events = listOf(
                AssistantEvent.OnPhotoPick("content://receipt"),
                AssistantEvent.OnSendClick,
            )
        ) {
            attachment shouldBe null
            messages[0] shouldBe ChatMessage.User(0, "", "content://receipt")
            messages[1] shouldBe ChatMessage.Assistant(1, "Saved 85 EGP.")
        }
    }

    @Test
    fun `an unreadable photo isn't sent`() {
        coEvery { receiptImages.jpeg(any()) } returns null

        viewModel.runTest(
            events = listOf(
                AssistantEvent.OnPhotoPick("content://broken"),
                AssistantEvent.OnSendClick,
            )
        ) {
            messages.last() shouldBe ChatMessage.Notice(1, NoticeKind.PhotoUnreadable)
        }
        coVerify(exactly = 0) { agent.send(any(), any(), any(), any()) }
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

    @Test
    fun `saves the conversation after each reply under the same id`() {
        val saved = mutableListOf<Conversation>()
        coEvery { history.save(capture(saved)) } returns Unit
        coEvery { agent.send("hi", null, config, any()) } returns Either.Right("Hello.")

        viewModel.runTest(
            events = listOf(
                AssistantEvent.OnExampleClick("lunch 25"),
                AssistantEvent.OnExampleClick("hi"),
            )
        ) {
            messages.size shouldBe 5
        }

        saved.size shouldBe 2
        saved[0].id shouldBe saved[1].id
        saved[1].messages.size shouldBe 5
        saved[1].updatedAt shouldBe 1_000
    }

    @Test
    fun `a new chat is saved as another conversation`() {
        val saved = mutableListOf<Conversation>()
        coEvery { history.save(capture(saved)) } returns Unit

        viewModel.runTest(
            events = listOf(
                AssistantEvent.OnExampleClick("lunch 25"),
                AssistantEvent.OnNewChatClick,
                AssistantEvent.OnExampleClick("lunch 25"),
            )
        ) {
            messages.size shouldBe 3
        }

        saved.map { it.id }.distinct().size shouldBe 2
    }

    @Test
    fun `shows the history and opens a saved conversation`() {
        val snapshot = AgentSnapshot(emptyList(), listOf("note"))
        val conversation = Conversation(
            id = "old",
            updatedAt = 500,
            messages = listOf(
                ChatMessage.User(0, "coffee 10", null),
                ChatMessage.Assistant(1, "Saved."),
            ),
            agent = snapshot,
        )
        coEvery { history.conversations() } returns listOf(conversation)
        val restored = slot<AgentSnapshot>()
        every { agent.restore(capture(restored)) } returns Unit

        viewModel.runTest(events = listOf(AssistantEvent.OnHistoryClick)) {
            this.history shouldBe persistentListOf(ConversationSummary("old", "coffee 10", 500, current = false))
        }
        viewModel.runTest(events = listOf(AssistantEvent.OnConversationClick("old"))) {
            this.history shouldBe null
            messages shouldBe conversation.messages
        }
        restored.captured shouldBe snapshot

        val saved = slot<Conversation>()
        coEvery { history.save(capture(saved)) } returns Unit
        viewModel.runTest(events = listOf(AssistantEvent.OnExampleClick("lunch 25"))) {
            messages[2] shouldBe ChatMessage.User(2, "lunch 25", null)
        }
        saved.captured.id shouldBe "old"
    }

    @Test
    fun `deleting the open conversation starts a new chat`() {
        val saved = slot<Conversation>()
        coEvery { history.save(capture(saved)) } returns Unit
        viewModel.runTest(events = listOf(AssistantEvent.OnExampleClick("lunch 25"))) {
            messages.size shouldBe 3
        }

        viewModel.runTest(events = listOf(AssistantEvent.OnConversationDelete(saved.captured.id))) {
            messages shouldBe persistentListOf()
            this.history shouldBe persistentListOf()
        }
        coVerify { history.delete(saved.captured.id) }
    }

    private fun addedMessage(): ChatMessage.Added = ChatMessage.Added(
        id = 1,
        transaction = transactionId,
        type = "expense",
        amount = "25.0 EGP",
        details = "",
        undone = false,
    )
}
