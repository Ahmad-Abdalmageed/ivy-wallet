package com.ivy.voicecommands

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ivy.navigation.IvyPreview
import com.ivy.navigation.navigation
import com.ivy.navigation.screenScopedViewModel
import com.ivy.ui.R
import kotlinx.collections.immutable.persistentListOf

@Composable
fun VoiceCommandsSettingsScreenImpl() {
    val viewModel: VoiceCommandsSettingsViewModel = screenScopedViewModel()
    val uiState = viewModel.uiState()

    VoiceCommandsSettingsUi(
        uiState = uiState,
        onEvent = viewModel::onEvent
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceCommandsSettingsUi(
    uiState: VoiceCommandsSettingsState,
    onEvent: (VoiceCommandsSettingsEvent) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.voice_commands))
                },
                navigationIcon = {
                    BackButton()
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = innerPadding,
        ) {
            item {
                Text(
                    text = stringResource(R.string.voice_commands_api_keys_description),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            items(
                items = uiState.apiKeys,
                key = { it.provider.name }
            ) { apiKey ->
                ApiKeyCard(
                    apiKey = apiKey,
                    onEvent = onEvent
                )
            }
        }
    }
}

@Composable
private fun BackButton() {
    val nav = navigation()

    IconButton(onClick = {
        nav.back()
    }) {
        Icon(
            imageVector = Icons.Filled.ArrowBack,
            contentDescription = "Back"
        )
    }
}

@Composable
private fun ApiKeyCard(
    apiKey: ApiKeyState,
    onEvent: (VoiceCommandsSettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = apiKey.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = apiKey.savedKeyHint?.let {
                    stringResource(R.string.api_key_saved, it)
                } ?: stringResource(R.string.api_key_not_set),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            ApiKeyTextField(
                apiKey = apiKey,
                onValueChange = {
                    onEvent(VoiceCommandsSettingsEvent.OnInputChange(apiKey.provider, it))
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                if (apiKey.savedKeyHint != null) {
                    OutlinedButton(onClick = {
                        onEvent(VoiceCommandsSettingsEvent.OnRemoveClick(apiKey.provider))
                    }) {
                        Text(text = stringResource(R.string.delete))
                    }
                }
                Button(
                    enabled = apiKey.input.isNotBlank(),
                    onClick = {
                        onEvent(VoiceCommandsSettingsEvent.OnSaveClick(apiKey.provider))
                    }
                ) {
                    Text(text = stringResource(R.string.save))
                }
            }
        }
    }
}

@Composable
private fun ApiKeyTextField(
    apiKey: ApiKeyState,
    onValueChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }

    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = apiKey.input,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(text = stringResource(R.string.api_key)) },
        isError = apiKey.error != null,
        supportingText = {
            apiKey.error?.let { Text(text = it) }
        },
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            autoCorrect = false,
            keyboardType = KeyboardType.Password
        ),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = null
                )
            }
        }
    )
}

@Preview
@Composable
private fun PreviewVoiceCommandsSettings(isDark: Boolean = false) {
    IvyPreview(isDark) {
        VoiceCommandsSettingsUi(
            uiState = VoiceCommandsSettingsState(
                apiKeys = persistentListOf(
                    ApiKeyState(
                        provider = VoiceApiProvider.OpenAI,
                        title = "OpenAI",
                        input = "",
                        savedKeyHint = "••••abcd",
                        error = null,
                    ),
                    ApiKeyState(
                        provider = VoiceApiProvider.Anthropic,
                        title = "Anthropic",
                        input = "sk-ant-123",
                        savedKeyHint = null,
                        error = null,
                    ),
                    ApiKeyState(
                        provider = VoiceApiProvider.Google,
                        title = "Google",
                        input = " ",
                        savedKeyHint = null,
                        error = "API key can't be empty",
                    ),
                )
            ),
            onEvent = {}
        )
    }
}
