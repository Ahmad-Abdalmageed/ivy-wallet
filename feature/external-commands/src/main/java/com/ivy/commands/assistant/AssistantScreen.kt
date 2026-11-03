package com.ivy.commands.assistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ivy.commands.command.CommandProcessor
import com.ivy.design.system.colors.IvyColors
import com.ivy.navigation.AssistantScreen
import com.ivy.navigation.screenScopedViewModel
import com.ivy.ui.R
import com.ivy.ui.component.BackButton

@Composable
fun AssistantScreen(screen: AssistantScreen) {
    val viewModel: AssistantViewModel = screenScopedViewModel()
    viewModel.listenOnStart(screen.listenOnStart)
    AssistantUi(
        state = viewModel.uiState(),
        onEvent = viewModel::onEvent,
        newPhotoUri = viewModel::newPhotoUri,
    )
}

@Composable
private fun AssistantUi(
    state: AssistantState,
    onEvent: (AssistantEvent) -> Unit,
    newPhotoUri: () -> String,
    modifier: Modifier = Modifier,
) {
    val currentOnEvent by rememberUpdatedState(onEvent)
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) {
            currentOnEvent(AssistantEvent.OnSpeechResult(text))
        }
    }
    val prompt = stringResource(R.string.assistant_input_hint)
    val listen = {
        try {
            speechLauncher.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            )
        } catch (_: ActivityNotFoundException) {
            currentOnEvent(AssistantEvent.OnSpeechUnavailable)
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { currentOnEvent(AssistantEvent.OnPhotoPick(it.toString())) }
    }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        cameraUri?.takeIf { saved }?.let { currentOnEvent(AssistantEvent.OnPhotoPick(it)) }
    }
    val photoActions = PhotoActions(
        takePhoto = {
            val uri = newPhotoUri()
            cameraUri = uri
            try {
                camera.launch(Uri.parse(uri))
            } catch (_: ActivityNotFoundException) {
                currentOnEvent(AssistantEvent.OnCameraUnavailable)
            }
        },
        pickPhoto = {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
    )
    LaunchedEffect(state.listenOnStart) {
        if (state.listenOnStart) {
            currentOnEvent(AssistantEvent.OnListenOnStartHandled)
            listen()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AssistantScaffold(state = state, onEvent = onEvent, onListen = listen, photoActions = photoActions)
        state.settings?.let { settings ->
            SettingsDialog(settings = settings, onEvent = onEvent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantScaffold(
    state: AssistantState,
    onEvent: (AssistantEvent) -> Unit,
    onListen: () -> Unit,
    photoActions: PhotoActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier
            .systemBarsPadding()
            .imePadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    BackButton(onClick = { onEvent(AssistantEvent.OnBackClick) })
                },
                title = { Text(text = stringResource(R.string.assistant_title)) },
                actions = {
                    IconButton(onClick = { onEvent(AssistantEvent.OnNewChatClick) }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.assistant_new_chat),
                        )
                    }
                    IconButton(onClick = { onEvent(AssistantEvent.OnSettingsClick) }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.assistant_settings),
                        )
                    }
                },
            )
        },
        bottomBar = {
            InputBar(
                input = state.input,
                attachment = state.attachment,
                sending = state.sending,
                onEvent = onEvent,
                onListen = onListen,
                photoActions = photoActions,
            )
        },
        content = { padding ->
            Box(modifier = Modifier.padding(padding)) {
                if (state.messages.isEmpty()) {
                    Welcome(hasApiKey = state.hasApiKey, onEvent = onEvent)
                } else {
                    Messages(state = state, onEvent = onEvent)
                }
            }
        },
    )
}

@Composable
private fun Welcome(
    hasApiKey: Boolean?,
    onEvent: (AssistantEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.assistant_welcome),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (hasApiKey == false) {
            TextButton(onClick = { onEvent(AssistantEvent.OnSettingsClick) }) {
                Text(text = stringResource(R.string.assistant_no_key))
            }
        }
        listOf(
            R.string.assistant_example_1,
            R.string.assistant_example_2,
            R.string.assistant_example_3,
            R.string.assistant_example_4,
        ).forEach { example ->
            val text = stringResource(example)
            AssistChip(
                onClick = { onEvent(AssistantEvent.OnExampleClick(text)) },
                label = { Text(text = text) },
            )
        }
    }
}

@Composable
private fun Messages(
    state: AssistantState,
    onEvent: (AssistantEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.sending) {
        listState.animateScrollToItem(state.messages.size)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = state.messages, key = { it.id }) { message ->
            MessageItem(message = message, onEvent = onEvent)
        }
        item(key = "sending") {
            if (state.sending) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.assistant_thinking),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageItem(
    message: ChatMessage,
    onEvent: (AssistantEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (message) {
        is ChatMessage.User -> Bubble(
            modifier = modifier,
            text = message.text,
            imageUri = message.imageUri,
            fromUser = true,
        )

        is ChatMessage.Assistant -> Bubble(
            modifier = modifier,
            text = message.text,
            imageUri = null,
            fromUser = false,
        )

        is ChatMessage.Error -> Notice(
            modifier = modifier,
            text = message.text,
            error = true,
        )

        is ChatMessage.Notice -> Notice(
            modifier = modifier,
            text = stringResource(message.kind.textRes()),
            error = true,
        )

        is ChatMessage.Deleted -> Notice(
            modifier = modifier,
            text = stringResource(R.string.assistant_deleted),
            error = false,
        )

        is ChatMessage.Added -> TransactionCard(
            modifier = modifier,
            message = message,
            onUndo = { onEvent(AssistantEvent.OnUndoClick(message)) },
        )
    }
}

@Composable
private fun Bubble(
    text: String,
    imageUri: String?,
    fromUser: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = BubbleMaxWidth.dp),
            shape = RoundedCornerShape(16.dp),
            color = if (fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        ) {
            Column {
                imageUri?.let {
                    AsyncImage(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = BubbleImageMaxHeight.dp),
                        model = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                    )
                }
                if (text.isNotBlank()) {
                    Text(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun Notice(
    text: String,
    error: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier.fillMaxWidth(),
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun TransactionCard(
    message: ChatMessage.Added,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when (message.type) {
        CommandProcessor.TYPE_INCOME -> R.string.income to IvyColors.Green.primary
        CommandProcessor.TYPE_TRANSFER -> R.string.transfer to IvyColors.Purple.primary
        else -> R.string.expense to IvyColors.Red.primary
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = message.amount,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = message.details,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (message.undone) {
                Text(
                    modifier = Modifier.padding(8.dp),
                    text = stringResource(R.string.assistant_undone),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                TextButton(onClick = onUndo) {
                    Text(text = stringResource(R.string.assistant_undo))
                }
            }
        }
    }
}

/** Opens the camera or the photo picker; the photo comes back as [AssistantEvent.OnPhotoPick]. */
@Stable
private class PhotoActions(
    val takePhoto: () -> Unit,
    val pickPhoto: () -> Unit,
)

@Composable
private fun InputBar(
    input: String,
    attachment: String?,
    sending: Boolean,
    onEvent: (AssistantEvent) -> Unit,
    onListen: () -> Unit,
    photoActions: PhotoActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        attachment?.let {
            AttachmentPreview(uri = it, onEvent = onEvent)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AttachButton(enabled = !sending, photoActions = photoActions)
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = input,
                onValueChange = { onEvent(AssistantEvent.OnInputChange(it)) },
                placeholder = { Text(text = stringResource(R.string.assistant_input_hint)) },
                maxLines = InputMaxLines,
                shape = RoundedCornerShape(24.dp),
            )
            IconButton(onClick = onListen, enabled = !sending) {
                Icon(
                    painter = painterResource(R.drawable.ic_vue_media_microphone),
                    contentDescription = stringResource(R.string.assistant_voice),
                )
            }
            IconButton(
                onClick = { onEvent(AssistantEvent.OnSendClick) },
                enabled = !sending && (input.isNotBlank() || attachment != null),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.assistant_send),
                )
            }
        }
    }
}

@Composable
private fun AttachButton(
    enabled: Boolean,
    photoActions: PhotoActions,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(
                imageVector = Icons.Filled.AddAPhoto,
                contentDescription = stringResource(R.string.assistant_attach_photo),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.assistant_take_photo)) },
                leadingIcon = { Icon(imageVector = Icons.Filled.PhotoCamera, contentDescription = null) },
                onClick = {
                    expanded = false
                    photoActions.takePhoto()
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.assistant_choose_photo)) },
                leadingIcon = { Icon(imageVector = Icons.Filled.PhotoLibrary, contentDescription = null) },
                onClick = {
                    expanded = false
                    photoActions.pickPhoto()
                },
            )
        }
    }
}

@Composable
private fun AttachmentPreview(
    uri: String,
    onEvent: (AssistantEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            modifier = Modifier
                .size(AttachmentPreviewSize.dp)
                .clip(RoundedCornerShape(8.dp)),
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
        )
        IconButton(onClick = { onEvent(AssistantEvent.OnPhotoRemove) }) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.assistant_remove_photo),
            )
        }
    }
}

@Composable
private fun SettingsDialog(
    settings: AssistantSettingsUi,
    onEvent: (AssistantEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        modifier = modifier,
        onDismissRequest = { onEvent(AssistantEvent.OnSettingsDismiss) },
        confirmButton = {
            TextButton(onClick = { onEvent(AssistantEvent.OnSettingsSave) }) {
                Text(text = stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(AssistantEvent.OnSettingsDismiss) }) {
                Text(text = stringResource(R.string.assistant_cancel))
            }
        },
        title = { Text(text = stringResource(R.string.assistant_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.assistant_settings_info),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { uriHandler.openUri(ApiKeyUrl) }) {
                    Text(text = stringResource(R.string.assistant_get_key))
                }
                OutlinedTextField(
                    value = settings.apiKey,
                    onValueChange = { onEvent(AssistantEvent.OnApiKeyChange(it)) },
                    label = { Text(text = stringResource(R.string.assistant_api_key)) },
                    placeholder = {
                        if (settings.hasSavedKey) Text(text = stringResource(R.string.assistant_api_key_saved))
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    value = settings.model,
                    onValueChange = { onEvent(AssistantEvent.OnModelChange(it)) },
                    label = { Text(text = stringResource(R.string.assistant_model)) },
                    placeholder = { Text(text = AssistantSettings.DEFAULT_MODEL) },
                    singleLine = true,
                )
                if (settings.hasSavedKey) {
                    TextButton(onClick = { onEvent(AssistantEvent.OnApiKeyRemove) }) {
                        Text(
                            text = stringResource(R.string.assistant_remove_key),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
    )
}

@StringRes
private fun NoticeKind.textRes(): Int = when (this) {
    NoticeKind.SpeechUnavailable -> R.string.assistant_speech_unavailable
    NoticeKind.CameraUnavailable -> R.string.assistant_camera_unavailable
    NoticeKind.PhotoUnreadable -> R.string.assistant_photo_unreadable
}

private const val ApiKeyUrl = "https://aistudio.google.com/apikey"
private const val BubbleMaxWidth = 300
private const val InputMaxLines = 4
private const val BubbleImageMaxHeight = 240
private const val AttachmentPreviewSize = 56
