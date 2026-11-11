package com.ivy.commands.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.base.time.TimeProvider
import com.ivy.commands.command.Command
import com.ivy.commands.command.CommandArgs
import com.ivy.commands.command.CommandProcessor
import com.ivy.data.model.TransactionId
import com.ivy.navigation.Navigation
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject

@Suppress("TooManyFunctions")
@Stable
@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val agent: AssistantAgent,
    private val settings: AssistantSettings,
    private val processor: CommandProcessor,
    private val receiptImages: ReceiptImages,
    private val navigation: Navigation,
    private val history: AssistantHistory,
    private val timeProvider: TimeProvider,
) : ComposeViewModel<AssistantState, AssistantEvent>() {
    private var messages by mutableStateOf<ImmutableList<ChatMessage>>(persistentListOf())
    private var input by mutableStateOf("")
    private var attachment by mutableStateOf<String?>(null)
    private var sending by mutableStateOf(false)
    private var hasApiKey by mutableStateOf<Boolean?>(null)
    private var settingsUi by mutableStateOf<AssistantSettingsUi?>(null)
    private var listenOnStart by mutableStateOf(false)
    private var historyUi by mutableStateOf<ImmutableList<ConversationSummary>?>(null)
    private var conversationId: String? = null
    private var listenRequested = false
    private var started = false
    private var nextId = 0L

    /**
     * Starts listening when the screen opens, e.g. from the launcher shortcut or as the
     * phone's assistant app. Call before [uiState].
     */
    fun listenOnStart(listen: Boolean) {
        if (!started) listenRequested = listen
    }

    @Composable
    override fun uiState(): AssistantState {
        LaunchedEffect(Unit) {
            started = true
            hasApiKey = settings.config() != null
            if (hasApiKey == true) {
                listenOnStart = listenRequested
            } else {
                openSettings()
            }
        }

        return AssistantState(
            messages = messages,
            input = input,
            attachment = attachment,
            sending = sending,
            hasApiKey = hasApiKey,
            settings = settingsUi,
            listenOnStart = listenOnStart,
            history = historyUi,
        )
    }

    override fun onEvent(event: AssistantEvent) {
        when (event) {
            AssistantEvent.OnBackClick -> navigation.back()
            is AssistantEvent.OnInputChange -> input = event.input
            AssistantEvent.OnSendClick -> send(input)
            is AssistantEvent.OnSpeechResult -> send(event.text)
            AssistantEvent.OnSpeechUnavailable -> add { ChatMessage.Notice(it, NoticeKind.SpeechUnavailable) }
            is AssistantEvent.OnPhotoPick -> attachment = event.uri
            AssistantEvent.OnPhotoRemove -> attachment = null
            AssistantEvent.OnCameraUnavailable -> add { ChatMessage.Notice(it, NoticeKind.CameraUnavailable) }
            AssistantEvent.OnListenOnStartHandled -> listenOnStart = false
            is AssistantEvent.OnExampleClick -> send(event.text)
            is AssistantEvent.OnUndoClick -> undo(event.message)
            AssistantEvent.OnNewChatClick -> newChat()
            AssistantEvent.OnHistoryClick -> viewModelScope.launch { showHistory() }
            AssistantEvent.OnHistoryDismiss -> historyUi = null
            is AssistantEvent.OnConversationClick -> openConversation(event.id)
            is AssistantEvent.OnConversationDelete -> deleteConversation(event.id)
            AssistantEvent.OnSettingsClick -> viewModelScope.launch { openSettings() }
            AssistantEvent.OnSettingsDismiss -> settingsUi = null
            is AssistantEvent.OnApiKeyChange -> settingsUi = settingsUi?.copy(apiKey = event.apiKey)
            is AssistantEvent.OnModelChange -> settingsUi = settingsUi?.copy(model = event.model)
            AssistantEvent.OnSettingsSave -> saveSettings()
            AssistantEvent.OnApiKeyRemove -> removeApiKey()
        }
    }

    private suspend fun openSettings() {
        settingsUi = AssistantSettingsUi(
            apiKey = "",
            model = settings.model(),
            hasSavedKey = settings.config() != null,
        )
    }

    private fun saveSettings() {
        val ui = settingsUi ?: return
        viewModelScope.launch {
            settings.save(apiKey = ui.apiKey, model = ui.model)
            hasApiKey = settings.config() != null
            settingsUi = null
        }
    }

    private fun removeApiKey() {
        viewModelScope.launch {
            settings.removeApiKey()
            hasApiKey = false
            settingsUi = settingsUi?.copy(hasSavedKey = false)
        }
    }

    /**
     * @return a content uri for the camera app to save a receipt photo to
     */
    fun newPhotoUri(): String = receiptImages.newPhotoUri().toString()

    private fun send(text: String) {
        val message = text.trim()
        val image = attachment
        if ((message.isEmpty() && image == null) || sending) return
        input = ""
        attachment = null
        val userMessageId = nextId
        add { ChatMessage.User(it, message, image) }
        sending = true
        viewModelScope.launch {
            reply(message, image, userMessageId)
            sending = false
            saveConversation()
        }
    }

    private suspend fun reply(message: String, image: String?, userMessageId: Long) {
        val config = settings.config()
        if (config == null) {
            hasApiKey = false
            openSettings()
            return
        }
        val jpeg = image?.let { receiptImages.jpeg(it) }
        if (image != null && jpeg == null) {
            add { ChatMessage.Notice(it, NoticeKind.PhotoUnreadable) }
            return
        }
        jpeg?.let { history.savePhoto(it) }?.let { saved -> replaceImage(userMessageId, saved) }
        agent.send(message, jpeg, config) { action -> show(action) }.fold(
            ifLeft = { error -> add { ChatMessage.Error(it, error) } },
            ifRight = { reply -> add { ChatMessage.Assistant(it, reply.ifBlank { DONE }) } },
        )
    }

    private fun show(action: AssistantAction) {
        when (action) {
            is AssistantAction.Added -> action.transaction.toMessage(nextId)?.let { message -> add { message } }
            is AssistantAction.Deleted -> {
                markUndone(action.transaction)
                add { ChatMessage.Deleted(it, action.transaction) }
            }
        }
    }

    private fun undo(added: ChatMessage.Added) {
        if (added.undone) return
        viewModelScope.launch {
            val args = CommandArgs(buildJsonObject { put("id", added.transaction.value.toString()) })
            processor.execute(Command.DeleteTransaction.method, args).fold(
                ifLeft = { error -> add { ChatMessage.Error(it, error) } },
                ifRight = {
                    markUndone(added.transaction)
                    agent.noteUndo(added.transaction)
                    saveConversation()
                },
            )
        }
    }

    private fun markUndone(transaction: TransactionId) {
        messages = messages.map {
            if (it is ChatMessage.Added && it.transaction == transaction) it.copy(undone = true) else it
        }.toImmutableList()
    }

    private fun newChat() {
        if (sending) return
        agent.reset()
        messages = persistentListOf()
        conversationId = null
    }

    /** Shows the photo's saved copy, which outlives the camera's cache and the gallery permission. */
    private fun replaceImage(messageId: Long, imageUri: String) {
        messages = messages.map {
            if (it is ChatMessage.User && it.id == messageId) it.copy(imageUri = imageUri) else it
        }.toImmutableList()
    }

    private suspend fun saveConversation() {
        if (messages.isEmpty()) return
        val id = conversationId ?: UUID.randomUUID().toString().also { conversationId = it }
        history.save(
            Conversation(
                id = id,
                updatedAt = timeProvider.utcNow().toEpochMilli(),
                messages = messages,
                agent = agent.snapshot(),
            )
        )
    }

    private suspend fun showHistory() {
        historyUi = history.conversations().map {
            ConversationSummary(
                id = it.id,
                title = it.title,
                updatedAt = it.updatedAt,
                current = it.id == conversationId,
            )
        }.toImmutableList()
    }

    private fun openConversation(id: String) {
        if (sending) return
        viewModelScope.launch {
            val conversation = history.conversations().firstOrNull { it.id == id } ?: return@launch
            agent.restore(conversation.agent)
            messages = conversation.messages.toImmutableList()
            nextId = (conversation.messages.maxOfOrNull { it.id } ?: -1L) + 1
            conversationId = conversation.id
            input = ""
            attachment = null
            historyUi = null
        }
    }

    private fun deleteConversation(id: String) {
        if (sending) return
        viewModelScope.launch {
            history.delete(id)
            if (id == conversationId) newChat()
            showHistory()
        }
    }

    private fun add(message: (id: Long) -> ChatMessage) {
        messages = (messages + message(nextId++)).toImmutableList()
    }

    private fun JsonObject.toMessage(id: Long): ChatMessage? {
        fun field(name: String): String? = (this[name] as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() && it != "null" }

        val tags = (this["tags"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        val account = listOfNotNull(field("account"), field("to_account")).joinToString(" → ")
        val toAmount = field("to_amount")?.let { " → $it ${field("to_currency").orEmpty()}" }.orEmpty()
        val transactionId = field("id")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        return ChatMessage.Added(
            id = id,
            transaction = TransactionId(transactionId),
            type = field("type").orEmpty(),
            amount = "${field("amount").orEmpty()} ${field("currency").orEmpty()}$toAmount",
            details = (
                listOf(account) + listOfNotNull(field("category"), field("title"), field("date")) +
                    tags.map { "#$it" }
                ).filter { it.isNotBlank() }.joinToString(" · "),
            undone = false,
        )
    }

    companion object {
        private const val DONE = "✓"
    }
}
