package com.ivy.voicecommands.voice

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ivy.navigation.screenScopedViewModel
import com.ivy.ui.R

/**
 * Provides [content] with a callback that starts a voice command:
 * speech recognition -> transaction draft -> pre-filled edit transaction screen.
 */
@Composable
fun VoiceTransactionHost(
    content: @Composable (onStartVoiceCommand: () -> Unit) -> Unit,
) {
    val viewModel: VoiceTransactionViewModel = screenScopedViewModel()
    val uiState = viewModel.uiState()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) {
            viewModel.onEvent(VoiceTransactionEvent.OnSpeechResult(text))
        }
    }
    val prompt = stringResource(R.string.voice_command_prompt)

    content {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        uiState.languageTag?.let { intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            viewModel.onEvent(VoiceTransactionEvent.OnSpeechUnavailable)
        }
    }

    if (uiState.processing) {
        ProcessingDialog()
    }

    uiState.message?.let { message ->
        MessageToast(
            message = message,
            onShow = { viewModel.onEvent(VoiceTransactionEvent.OnMessageShown) }
        )
    }
}

@Composable
private fun ProcessingDialog() {
    Dialog(onDismissRequest = {}) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.width(16.dp))
                Text(text = stringResource(R.string.voice_command_processing))
            }
        }
    }
}

@Composable
private fun MessageToast(
    message: VoiceTransactionMessage,
    onShow: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnShow by rememberUpdatedState(onShow)
    val text = when (message) {
        VoiceTransactionMessage.SpeechUnavailable -> stringResource(R.string.voice_command_unavailable)
        VoiceTransactionMessage.NoAmount -> stringResource(R.string.voice_command_no_amount)
        is VoiceTransactionMessage.AiFailed -> message.error
        is VoiceTransactionMessage.UsedOfflineParser ->
            stringResource(R.string.voice_command_used_offline_parser, message.error)
    }
    LaunchedEffect(message) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        currentOnShow()
    }
}
