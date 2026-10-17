package com.ivy.voicecommands

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.viewModelScope
import arrow.core.raise.either
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import javax.inject.Inject

@Stable
@HiltViewModel
class VoiceCommandsSettingsViewModel @Inject constructor(
    private val apiKeysRepository: VoiceApiKeysRepository,
) : ComposeViewModel<VoiceCommandsSettingsState, VoiceCommandsSettingsEvent>() {
    private val inputs = mutableStateMapOf<VoiceApiProvider, String>()
    private val savedKeys = mutableStateMapOf<VoiceApiProvider, NotBlankTrimmedString>()
    private val errors = mutableStateMapOf<VoiceApiProvider, String>()

    @Composable
    override fun uiState(): VoiceCommandsSettingsState {
        LaunchedEffect(Unit) {
            loadSavedKeys()
        }

        return VoiceCommandsSettingsState(
            apiKeys = getApiKeys()
        )
    }

    private fun getApiKeys(): ImmutableList<ApiKeyState> = VoiceApiProvider.entries.map { provider ->
        ApiKeyState(
            provider = provider,
            title = provider.displayName,
            input = inputs[provider].orEmpty(),
            savedKeyHint = savedKeys[provider]?.let(::maskKey),
            error = errors[provider],
        )
    }.toImmutableList()

    override fun onEvent(event: VoiceCommandsSettingsEvent) {
        when (event) {
            is VoiceCommandsSettingsEvent.OnInputChange -> onInputChange(event)
            is VoiceCommandsSettingsEvent.OnSaveClick -> onSaveClick(event.provider)
            is VoiceCommandsSettingsEvent.OnRemoveClick -> onRemoveClick(event.provider)
        }
    }

    private suspend fun loadSavedKeys() {
        VoiceApiProvider.entries.forEach { provider ->
            apiKeysRepository.findKey(provider).fold(
                ifLeft = { errors[provider] = it },
                ifRight = { key ->
                    if (key != null) savedKeys[provider] = key
                }
            )
        }
    }

    private fun onInputChange(event: VoiceCommandsSettingsEvent.OnInputChange) {
        inputs[event.provider] = event.input
        errors.remove(event.provider)
    }

    private fun onSaveClick(provider: VoiceApiProvider) {
        viewModelScope.launch {
            either {
                val key = NotBlankTrimmedString.from(inputs[provider].orEmpty())
                    .mapLeft { "API key can't be empty" }
                    .bind()
                apiKeysRepository.saveKey(provider, key).bind()
                key
            }.fold(
                ifLeft = { errors[provider] = it },
                ifRight = { key ->
                    savedKeys[provider] = key
                    inputs.remove(provider)
                    errors.remove(provider)
                }
            )
        }
    }

    private fun onRemoveClick(provider: VoiceApiProvider) {
        viewModelScope.launch {
            apiKeysRepository.removeKey(provider).fold(
                ifLeft = { errors[provider] = it },
                ifRight = {
                    savedKeys.remove(provider)
                    errors.remove(provider)
                }
            )
        }
    }

    private fun maskKey(key: NotBlankTrimmedString): String {
        val suffix = if (key.value.length > VISIBLE_KEY_CHARS) {
            key.value.takeLast(VISIBLE_KEY_CHARS)
        } else {
            ""
        }
        return "••••$suffix"
    }

    companion object {
        private const val VISIBLE_KEY_CHARS = 4
    }
}
