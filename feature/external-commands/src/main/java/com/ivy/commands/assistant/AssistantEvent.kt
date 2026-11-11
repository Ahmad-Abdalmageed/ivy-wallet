package com.ivy.commands.assistant

sealed interface AssistantEvent {
    data object OnBackClick : AssistantEvent
    data class OnInputChange(val input: String) : AssistantEvent
    data object OnSendClick : AssistantEvent
    data class OnSpeechResult(val text: String) : AssistantEvent
    data object OnSpeechUnavailable : AssistantEvent
    data class OnPhotoPick(val uri: String) : AssistantEvent
    data object OnPhotoRemove : AssistantEvent
    data object OnCameraUnavailable : AssistantEvent
    data object OnListenOnStartHandled : AssistantEvent
    data class OnExampleClick(val text: String) : AssistantEvent
    data class OnUndoClick(val message: ChatMessage.Added) : AssistantEvent
    data object OnNewChatClick : AssistantEvent
    data object OnHistoryClick : AssistantEvent
    data object OnHistoryDismiss : AssistantEvent
    data class OnConversationClick(val id: String) : AssistantEvent
    data class OnConversationDelete(val id: String) : AssistantEvent
    data object OnSettingsClick : AssistantEvent
    data object OnSettingsDismiss : AssistantEvent
    data class OnApiKeyChange(val apiKey: String) : AssistantEvent
    data class OnModelChange(val model: String) : AssistantEvent
    data object OnSettingsSave : AssistantEvent
    data object OnApiKeyRemove : AssistantEvent
}
