package com.ivy.commands.ui

sealed interface ExternalCommandsEvent {
    data object OnBackClick : ExternalCommandsEvent
    data class OnEnabledChange(val enabled: Boolean) : ExternalCommandsEvent
    data object OnKeyVisibilityToggle : ExternalCommandsEvent
    data object OnNewKeyClick : ExternalCommandsEvent
}
