package com.ivy.commands.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.commands.command.Command
import com.ivy.commands.command.CommandArgs
import com.ivy.commands.command.CommandProcessor
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
import javax.inject.Inject

@Suppress("TooManyFunctions")
@Stable
@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val agent: AssistantAgent,
    private val settings: AssistantSettings,
    private val processor: CommandProcessor,
    private val navigation: Navigation,
) : ComposeViewModel<AssistantState, AssistantEvent>() {
    private var messages by mutableStateOf<ImmutableList<ChatMessage>>(persistentListOf())
    private var input by mutableStateOf("")
    private var sending by mutableStateOf(false)
    private var hasApiKey by mutableStateOf<Boolean?>(null)
    private var settingsUi by mutableStateOf<AssistantSettingsUi?>(null)
    private var listenOnStart by mutableStateOf(false)
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
            sending = sending,
            hasApiKey = hasApiKey,
            settings = settingsUi,
            listenOnStart = listenOnStart,
        )
    }

    override fun onEvent(event: AssistantEvent) {
        when (event) {
            AssistantEvent.OnBackClick -> navigation.back()
            is AssistantEvent.OnInputChange -> input = event.input
            AssistantEvent.OnSendClick -> send(input)
            is AssistantEvent.OnSpeechResult -> send(event.text)
            AssistantEvent.OnSpeechUnavailable -> add { ChatMessage.SpeechUnavailable(it) }
            AssistantEvent.OnListenOnStartHandled -> listenOnStart = false
            is AssistantEvent.OnExampleClick -> send(event.text)
            is AssistantEvent.OnUndoClick -> undo(event.messageId)
            AssistantEvent.OnNewChatClick -> newChat()
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

    private fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || sending) return
        input = ""
        add { ChatMessage.User(it, message) }
        sending = true
        viewModelScope.launch {
            val config = settings.config()
            if (config == null) {
                hasApiKey = false
                openSettings()
                sending = false
                return@launch
            }
            agent.send(message, config) { action -> show(action) }.fold(
                ifLeft = { error -> add { ChatMessage.Error(it, error) } },
                ifRight = { reply -> add { ChatMessage.Assistant(it, reply.ifBlank { DONE }) } },
            )
            sending = false
        }
    }

    private fun show(action: AssistantAction) {
        when (action) {
            is AssistantAction.Added -> add { action.transaction.toMessage(it) }
            is AssistantAction.Deleted -> {
                markUndone(action.transactionId)
                add { ChatMessage.Deleted(it, action.transactionId) }
            }
        }
    }

    private fun undo(messageId: Long) {
        val added = messages.find { it.id == messageId } as? ChatMessage.Added ?: return
        if (added.undone) return
        viewModelScope.launch {
            val args = CommandArgs(buildJsonObject { put("id", added.transactionId) })
            processor.execute(Command.DeleteTransaction.method, args).fold(
                ifLeft = { error -> add { ChatMessage.Error(it, error) } },
                ifRight = {
                    markUndone(added.transactionId)
                    agent.noteUndo(added.transactionId)
                },
            )
        }
    }

    private fun markUndone(transactionId: String) {
        messages = messages.map {
            if (it is ChatMessage.Added && it.transactionId == transactionId) it.copy(undone = true) else it
        }.toImmutableList()
    }

    private fun newChat() {
        if (sending) return
        agent.reset()
        messages = persistentListOf()
    }

    private fun add(message: (id: Long) -> ChatMessage) {
        messages = (messages + message(nextId++)).toImmutableList()
    }

    private fun JsonObject.toMessage(id: Long): ChatMessage.Added {
        fun field(name: String): String? = (this[name] as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() && it != "null" }

        val tags = (this["tags"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        val account = listOfNotNull(field("account"), field("to_account")).joinToString(" → ")
        val toAmount = field("to_amount")?.let { " → $it ${field("to_currency").orEmpty()}" }.orEmpty()
        return ChatMessage.Added(
            id = id,
            transactionId = field("id").orEmpty(),
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
