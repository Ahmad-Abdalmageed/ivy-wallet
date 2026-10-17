package com.ivy.voicecommands.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.base.time.TimeProvider
import com.ivy.navigation.EditTransactionScreen
import com.ivy.navigation.Navigation
import com.ivy.ui.ComposeViewModel
import com.ivy.voicecommands.VoiceApiKeysRepository
import com.ivy.voicecommands.VoiceLanguage
import com.ivy.voicecommands.parser.VoiceParseError
import com.ivy.voicecommands.parser.VoiceTransactionDraft
import com.ivy.voicecommands.parser.VoiceTransactionParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class VoiceTransactionState(
    val processing: Boolean,
    val message: VoiceTransactionMessage?,
    /** BCP 47 tag of the language to recognize, null for the phone's language. */
    val languageTag: String?,
)

sealed interface VoiceTransactionMessage {
    data object SpeechUnavailable : VoiceTransactionMessage
    data object NoAmount : VoiceTransactionMessage
    data class AiFailed(val error: String) : VoiceTransactionMessage

    /** The transaction was understood offline because the AI provider failed. */
    data class UsedOfflineParser(val error: String) : VoiceTransactionMessage
}

sealed interface VoiceTransactionEvent {
    data class OnSpeechResult(val text: String) : VoiceTransactionEvent
    data object OnSpeechUnavailable : VoiceTransactionEvent
    data object OnMessageShown : VoiceTransactionEvent
}

@Stable
@HiltViewModel
class VoiceTransactionViewModel @Inject constructor(
    private val parser: VoiceTransactionParser,
    private val navigation: Navigation,
    private val timeProvider: TimeProvider,
    private val settingsRepository: VoiceApiKeysRepository,
) : ComposeViewModel<VoiceTransactionState, VoiceTransactionEvent>() {
    private var processing by mutableStateOf(false)
    private var message by mutableStateOf<VoiceTransactionMessage?>(null)
    private var language by mutableStateOf(VoiceLanguage.Device)

    // Waits until the UI shows the message before leaving the screen
    private var pendingScreen: EditTransactionScreen? = null

    @Composable
    override fun uiState(): VoiceTransactionState {
        LaunchedEffect(Unit) {
            settingsRepository.findLanguage().onRight { language = it }
        }

        return VoiceTransactionState(
            processing = processing,
            message = message,
            languageTag = language.tag,
        )
    }

    override fun onEvent(event: VoiceTransactionEvent) {
        when (event) {
            is VoiceTransactionEvent.OnSpeechResult -> onSpeechResult(event.text)
            VoiceTransactionEvent.OnSpeechUnavailable -> {
                message = VoiceTransactionMessage.SpeechUnavailable
            }

            VoiceTransactionEvent.OnMessageShown -> onMessageShown()
        }
    }

    private fun onSpeechResult(text: String) {
        if (processing) return
        processing = true
        viewModelScope.launch {
            parser.parse(text).fold(
                ifLeft = { error ->
                    message = when (error) {
                        VoiceParseError.Blank,
                        VoiceParseError.NoAmount -> VoiceTransactionMessage.NoAmount

                        is VoiceParseError.AiFailed -> VoiceTransactionMessage.AiFailed(error.message)
                    }
                },
                ifRight = { result ->
                    val screen = result.draft.toScreen()
                    if (result.aiError == null) {
                        navigation.navigateTo(screen)
                    } else {
                        pendingScreen = screen
                        message = VoiceTransactionMessage.UsedOfflineParser(result.aiError)
                    }
                }
            )
            processing = false
        }
    }

    private fun onMessageShown() {
        message = null
        pendingScreen?.let(navigation::navigateTo)
        pendingScreen = null
    }

    private fun VoiceTransactionDraft.toScreen(): EditTransactionScreen = EditTransactionScreen(
        initialTransactionId = null,
        type = type,
        accountId = account?.value,
        categoryId = category?.value,
        toAccountId = toAccount?.value,
        amount = amount.value,
        title = title?.value,
        dateTime = date?.let { date ->
            // keep the current time of the day for past dates
            date.atTime(timeProvider.localTimeNow())
                .atZone(timeProvider.getZoneId())
                .toInstant()
        },
    )
}
