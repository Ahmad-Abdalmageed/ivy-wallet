package com.ivy.commands.assistant

import android.content.Context
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.model.TransactionId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A saved assistant conversation: what the chat shows and what Gemini needs to continue it.
 */
data class Conversation(
    val id: String,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
    val agent: AgentSnapshot,
) {
    /** The first thing the user wrote; empty if they started with a photo only. */
    val title: String
        get() = messages.filterIsInstance<ChatMessage.User>().firstOrNull()?.text.orEmpty()
}

/**
 * The last [MAX_CONVERSATIONS] assistant conversations, newest first, in the app's private storage.
 * Receipt photos are kept as small JPEGs next to the history, so they still show after the camera's
 * cache or the gallery permission is gone; the photo bytes themselves aren't sent again to Gemini
 * when a saved conversation is continued.
 */
@Singleton
class AssistantHistory internal constructor(
    private val directory: File,
    private val dispatchers: DispatchersProvider,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        dispatchers: DispatchersProvider,
    ) : this(File(context.filesDir, DIRECTORY), dispatchers)

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(directory, FILE)
    private val photos get() = File(directory, PHOTOS)

    /** @return the saved conversations, newest first */
    suspend fun conversations(): List<Conversation> = mutex.withLock {
        withContext(dispatchers.io) { read() }
    }

    /**
     * Saves [conversation], replacing the one with the same id, and drops the oldest beyond
     * [MAX_CONVERSATIONS] together with their photos.
     */
    suspend fun save(conversation: Conversation) = mutex.withLock {
        withContext(dispatchers.io) {
            val all = (listOf(conversation) + read().filter { it.id != conversation.id })
                .sortedByDescending { it.updatedAt }
            val kept = all.take(MAX_CONVERSATIONS)
            write(kept)
            all.drop(MAX_CONVERSATIONS).forEach { deletePhotos(it) }
        }
    }

    suspend fun delete(id: String) = mutex.withLock {
        withContext(dispatchers.io) {
            val all = read()
            write(all.filter { it.id != id })
            all.filter { it.id == id }.forEach { deletePhotos(it) }
        }
    }

    /**
     * Keeps a copy of a photo sent in a conversation.
     * @return a uri of the copy for the chat to show, or null if it couldn't be saved
     */
    suspend fun savePhoto(jpeg: ByteArray): String? = withContext(dispatchers.io) {
        try {
            val photo = File(photos.apply { mkdirs() }, "${UUID.randomUUID()}.jpg")
            photo.writeBytes(jpeg)
            photo.toURI().toString()
        } catch (_: IOException) {
            null
        }
    }

    private fun read(): List<Conversation> {
        val text = try {
            file.takeIf { it.exists() }?.readText() ?: return emptyList()
        } catch (_: IOException) {
            return emptyList()
        }
        val array = try {
            json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
        } catch (_: SerializationException) {
            return emptyList()
        }
        return array.mapNotNull { (it as? JsonObject)?.toConversation() }
    }

    /** The history is best effort: a failed write keeps the previous history. */
    private fun write(conversations: List<Conversation>) {
        try {
            directory.mkdirs()
            val temp = File(directory, "$FILE.tmp")
            temp.writeText(JsonArray(conversations.map { it.toJson() }).toString())
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (_: IOException) {
            // Keep the chat working without saving it.
        }
    }

    private fun deletePhotos(conversation: Conversation) {
        conversation.messages
            .mapNotNull { (it as? ChatMessage.User)?.imageUri }
            .mapNotNull { uri -> runCatching { File(java.net.URI(uri)) }.getOrNull() }
            .filter { it.parentFile == photos }
            .forEach { it.delete() }
    }

    private fun Conversation.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("updatedAt", updatedAt)
        put("messages", JsonArray(messages.map { it.toJson() }))
        put("contents", JsonArray(agent.contents.map { it.withoutImages() }))
        putJsonArray("notes") { agent.notes.forEach { add(JsonPrimitive(it)) } }
    }

    private fun JsonObject.toConversation(): Conversation? {
        val id = string("id") ?: return null
        return Conversation(
            id = id,
            updatedAt = (this["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
            messages = (this["messages"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.toMessage() },
            agent = AgentSnapshot(
                contents = (this["contents"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>(),
                notes = (this["notes"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            ),
        )
    }

    private fun ChatMessage.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        when (val message = this@toJson) {
            is ChatMessage.User -> {
                put("kind", "user")
                put("text", message.text)
                message.imageUri?.let { put("image", it) }
            }

            is ChatMessage.Assistant -> {
                put("kind", "assistant")
                put("text", message.text)
            }

            is ChatMessage.Error -> {
                put("kind", "error")
                put("text", message.text)
            }

            is ChatMessage.Added -> {
                put("kind", "added")
                put("transaction", message.transaction.value.toString())
                put("type", message.type)
                put("amount", message.amount)
                put("details", message.details)
                put("undone", message.undone)
            }

            is ChatMessage.Deleted -> {
                put("kind", "deleted")
                put("transaction", message.transaction.value.toString())
            }

            is ChatMessage.Notice -> {
                put("kind", "notice")
                put("notice", message.kind.name)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun JsonObject.toMessage(): ChatMessage? {
        val id = (this["id"] as? JsonPrimitive)?.longOrNull ?: return null
        fun transaction() = string("transaction")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.let(::TransactionId)
        return when (string("kind")) {
            "user" -> ChatMessage.User(id, string("text").orEmpty(), string("image"))
            "assistant" -> ChatMessage.Assistant(id, string("text").orEmpty())
            "error" -> ChatMessage.Error(id, string("text").orEmpty())
            "added" -> ChatMessage.Added(
                id = id,
                transaction = transaction() ?: return null,
                type = string("type").orEmpty(),
                amount = string("amount").orEmpty(),
                details = string("details").orEmpty(),
                undone = (this["undone"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )

            "deleted" -> ChatMessage.Deleted(id, transaction() ?: return null)
            "notice" -> NoticeKind.entries.firstOrNull { it.name == string("notice") }
                ?.let { ChatMessage.Notice(id, it) }

            else -> null
        }
    }

    /** Replaces photos sent to Gemini with a short note, to keep the history small. */
    private fun JsonObject.withoutImages(): JsonObject {
        val parts = this["parts"] as? JsonArray ?: return this
        if (parts.none { (it as? JsonObject)?.containsKey("inlineData") == true }) return this
        return JsonObject(
            this + ("parts" to buildJsonArray {
                parts.forEach { part ->
                    if ((part as? JsonObject)?.containsKey("inlineData") == true) {
                        addJsonObject { put("text", PHOTO_NOTE) }
                    } else {
                        add(part)
                    }
                }
            })
        )
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val MAX_CONVERSATIONS = 10
        private const val DIRECTORY = "assistant_history"
        private const val FILE = "conversations.json"
        private const val PHOTOS = "photos"
        private const val PHOTO_NOTE = "[The user attached a photo here; it's no longer available.]"
    }
}
