package com.ivy.commands.assistant

import androidx.compose.runtime.Immutable
import com.ivy.data.model.TransactionId
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class AssistantState(
    val messages: ImmutableList<ChatMessage>,
    val input: String,
    /** A photo to send with the next message, as a content uri. */
    val attachment: String?,
    val sending: Boolean,
    /** null while loading */
    val hasApiKey: Boolean?,
    val settings: AssistantSettingsUi?,
    val listenOnStart: Boolean,
)

@Immutable
data class AssistantSettingsUi(
    val apiKey: String,
    val model: String,
    val hasSavedKey: Boolean,
)

@Immutable
sealed interface ChatMessage {
    val id: Long

    @Immutable
    data class User(override val id: Long, val text: String, val imageUri: String?) : ChatMessage

    @Immutable
    data class Assistant(override val id: Long, val text: String) : ChatMessage

    @Immutable
    data class Error(override val id: Long, val text: String) : ChatMessage

    /** A transaction the assistant added; can be undone. */
    @Immutable
    data class Added(
        override val id: Long,
        val transaction: TransactionId,
        val type: String,
        val amount: String,
        val details: String,
        val undone: Boolean,
    ) : ChatMessage

    @Immutable
    data class Deleted(override val id: Long, val transaction: TransactionId) : ChatMessage

    @Immutable
    data class Notice(override val id: Long, val kind: NoticeKind) : ChatMessage
}

enum class NoticeKind {
    SpeechUnavailable,
    CameraUnavailable,
    PhotoUnreadable,
}
