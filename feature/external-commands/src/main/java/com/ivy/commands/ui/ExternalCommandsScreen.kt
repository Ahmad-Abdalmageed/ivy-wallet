package com.ivy.commands.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ivy.navigation.screenScopedViewModel
import com.ivy.ui.R
import com.ivy.ui.component.BackButton

@Composable
fun ExternalCommandsScreen() {
    val viewModel: ExternalCommandsViewModel = screenScopedViewModel()
    ExternalCommandsUi(
        state = viewModel.uiState(),
        onEvent = viewModel::onEvent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExternalCommandsUi(
    state: ExternalCommandsState,
    onEvent: (ExternalCommandsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.systemBarsPadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    BackButton(onClick = { onEvent(ExternalCommandsEvent.OnBackClick) })
                },
                title = { Text(text = stringResource(R.string.external_commands)) },
            )
        },
        content = { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "enable") {
                    EnableCard(enabled = state.enabled, onEvent = onEvent)
                }
                item(key = "key") {
                    AccessKeyCard(
                        accessKey = state.accessKey,
                        keyVisible = state.keyVisible,
                        onEvent = onEvent,
                    )
                }
                item(key = "setup") {
                    SetupCard(exampleCommand = state.exampleCommand, authority = state.authority)
                }
            }
        },
    )
}

@Composable
private fun EnableCard(
    enabled: Boolean,
    onEvent: (ExternalCommandsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.external_commands_enable),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.external_commands_enable_description),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { onEvent(ExternalCommandsEvent.OnEnabledChange(it)) },
            )
        }
    }
}

@Composable
private fun AccessKeyCard(
    accessKey: String,
    keyVisible: Boolean,
    onEvent: (ExternalCommandsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.external_commands_access_key),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.external_commands_access_key_description),
                style = MaterialTheme.typography.bodySmall,
            )
            SelectionContainer {
                Text(
                    modifier = Modifier.padding(vertical = 12.dp),
                    text = if (keyVisible) accessKey else MASK + accessKey.takeLast(VisibleKeyChars),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onEvent(ExternalCommandsEvent.OnKeyVisibilityToggle) }) {
                    Text(
                        text = stringResource(
                            if (keyVisible) R.string.external_commands_hide else R.string.external_commands_show
                        )
                    )
                }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(accessKey)) }) {
                    Text(text = stringResource(R.string.external_commands_copy))
                }
                TextButton(onClick = { onEvent(ExternalCommandsEvent.OnNewKeyClick) }) {
                    Text(text = stringResource(R.string.external_commands_new_key))
                }
            }
        }
    }
}

@Composable
private fun SetupCard(
    exampleCommand: String,
    authority: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.external_commands_setup),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                modifier = Modifier.padding(vertical = 8.dp),
                text = stringResource(R.string.external_commands_setup_steps, authority),
                style = MaterialTheme.typography.bodyMedium,
            )
            SelectionContainer {
                Text(
                    text = exampleCommand,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(exampleCommand)) }) {
                Text(text = stringResource(R.string.external_commands_copy_command))
            }
        }
    }
}

private const val MASK = "••••••••"
private const val VisibleKeyChars = 4
