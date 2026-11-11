package com.ivy.commands.assistant

import com.ivy.base.TestDispatchersProvider
import com.ivy.data.model.TransactionId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class AssistantHistoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val history by lazy { AssistantHistory(folder.root, TestDispatchersProvider) }

    private val transactionId = TransactionId(UUID.fromString("5e6f3b52-7c0d-4f7e-9a3b-0f1e2d3c4b5a"))

    private fun conversation(id: String, updatedAt: Long, imageUri: String? = null) = Conversation(
        id = id,
        updatedAt = updatedAt,
        messages = listOf(
            ChatMessage.User(0, "lunch 25 $id", imageUri),
            ChatMessage.Added(1, transactionId, "expense", "25.0 EGP", "Cash · Food", undone = true),
            ChatMessage.Deleted(2, transactionId),
            ChatMessage.Notice(3, NoticeKind.PhotoUnreadable),
            ChatMessage.Error(4, "Oops"),
            ChatMessage.Assistant(5, "Saved."),
        ),
        agent = AgentSnapshot(
            contents = listOf(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", "image/jpeg")
                                put("data", "BASE64PHOTO")
                            }
                        }
                        addJsonObject { put("text", "lunch 25") }
                    }
                }
            ),
            notes = listOf("a note"),
        ),
    )

    @Test
    fun `saves and reads back a conversation without the photo bytes`() = runTest {
        history.save(conversation("a", updatedAt = 1))

        val saved = history.conversations().single()
        saved.id shouldBe "a"
        saved.title shouldBe "lunch 25 a"
        saved.messages shouldBe conversation("a", 1).messages
        saved.agent.notes shouldBe listOf("a note")
        saved.agent.contents.single().toString() shouldNotContain "BASE64PHOTO"
        saved.agent.contents.single().toString() shouldContain "lunch 25"
    }

    @Test
    fun `keeps only the last 10 conversations, newest first`() = runTest {
        (1..12).forEach { history.save(conversation("c$it", updatedAt = it.toLong())) }

        history.conversations().map { it.id } shouldBe (12 downTo 3).map { "c$it" }
    }

    @Test
    fun `saving again replaces the conversation`() = runTest {
        history.save(conversation("a", updatedAt = 1))
        history.save(conversation("b", updatedAt = 2))
        history.save(conversation("a", updatedAt = 3))

        history.conversations().map { it.id } shouldBe listOf("a", "b")
    }

    @Test
    fun `deletes a conversation with its photo`() = runTest {
        val photo = history.savePhoto(byteArrayOf(1, 2, 3))!!
        history.save(conversation("a", updatedAt = 1, imageUri = photo))
        File(java.net.URI(photo)).exists() shouldBe true

        history.delete("a")

        history.conversations() shouldBe emptyList()
        File(java.net.URI(photo)).exists() shouldBe false
    }

    @Test
    fun `a broken file reads as empty`() = runTest {
        File(folder.root, "conversations.json").writeText("{not json")

        history.conversations() shouldBe emptyList()
    }
}
