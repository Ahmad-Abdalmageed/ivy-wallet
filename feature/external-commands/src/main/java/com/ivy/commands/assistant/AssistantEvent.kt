package com.ivy.commands.assistant

sealed interface AssistantEvent {
    data object OnBackClick : AssistantEvent
    data class OnInputChange(val input: String) : AssistantEvent
    data object OnSendClick : AssistantEvent
    data class OnSpeechResult(val text: String) : AssistantEvent
    data object OnSpeechUnavailable : AssistantEvent
    data object OnListenOnStartHandled : AssistantEvent
    data class OnExampleClick(val text: String) : AssistantEvent
    data class OnUndoClick(val messageId: Long) : AssistantEvent
    data object OnNewChatClick : AssistantEvent
    data object OnSettingsClick : AssistantEvent
    data object OnSettingsDismiss : AssistantEvent
    data class OnApiKeyChange(val apiKey: String) : AssistantEvent
    data class OnModelChange(val model: String) : AssistantEvent
    data object OnSettingsSave : AssistantEvent
    data object OnApiKeyRemove : AssistantEvent
}
