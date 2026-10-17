package com.ivy.voicecommands

sealed interface VoiceCommandsSettingsEvent {
    data class OnInputChange(
        val provider: VoiceApiProvider,
        val input: String,
    ) : VoiceCommandsSettingsEvent

    data class OnSaveClick(val provider: VoiceApiProvider) : VoiceCommandsSettingsEvent
    data class OnRemoveClick(val provider: VoiceApiProvider) : VoiceCommandsSettingsEvent
}
