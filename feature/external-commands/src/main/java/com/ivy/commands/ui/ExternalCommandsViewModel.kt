package com.ivy.commands.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.commands.ExternalCommandsSettings
import com.ivy.navigation.Navigation
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

@Stable
@HiltViewModel
class ExternalCommandsViewModel @Inject constructor(
    private val settings: ExternalCommandsSettings,
    private val navigation: Navigation,
    @ApplicationContext context: Context,
) : ComposeViewModel<ExternalCommandsState, ExternalCommandsEvent>() {
    private val authority = "${context.packageName}.commands"

    private var enabled by mutableStateOf(false)
    private var accessKey by mutableStateOf("")
    private var keyVisible by mutableStateOf(false)

    @Composable
    override fun uiState(): ExternalCommandsState {
        LaunchedEffect(Unit) {
            enabled = settings.isEnabled()
            accessKey = settings.accessKey()
        }

        return ExternalCommandsState(
            enabled = enabled,
            accessKey = accessKey,
            keyVisible = keyVisible,
            authority = authority,
            exampleCommand = "adb shell content call --uri content://$authority " +
                "--method list_accounts --arg '{\"key\":\"$accessKey\"}'",
        )
    }

    override fun onEvent(event: ExternalCommandsEvent) {
        when (event) {
            ExternalCommandsEvent.OnBackClick -> navigation.back()
            is ExternalCommandsEvent.OnEnabledChange -> viewModelScope.launch {
                settings.setEnabled(event.enabled)
                enabled = event.enabled
            }

            ExternalCommandsEvent.OnKeyVisibilityToggle -> keyVisible = !keyVisible
            ExternalCommandsEvent.OnNewKeyClick -> viewModelScope.launch {
                accessKey = settings.regenerateAccessKey()
                keyVisible = true
            }
        }
    }
}
