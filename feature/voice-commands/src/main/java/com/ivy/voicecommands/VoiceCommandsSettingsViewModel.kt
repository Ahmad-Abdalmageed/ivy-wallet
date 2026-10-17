package com.ivy.voicecommands

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
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
    private val models = mutableStateMapOf<VoiceApiProvider, String>()
    private val errors = mutableStateMapOf<VoiceApiProvider, String>()
    private var activeProvider by mutableStateOf<VoiceApiProvider?>(null)

    @Composable
    override fun uiState(): VoiceCommandsSettingsState {
        LaunchedEffect(Unit) {
            loadSettings()
        }

        return VoiceCommandsSettingsState(
            apiKeys = getApiKeys()
        )
    }

    private fun getApiKeys(): ImmutableList<ApiKeyState> = VoiceApiProvider.entries.map { provider ->
        ApiKeyState(
            provider = provider,
            title = provider.displayName,
            hasFreeTier = provider.hasFreeTier,
            input = inputs[provider].orEmpty(),
            savedKeyHint = savedKeys[provider]?.let(::maskKey),
            model = models[provider].orEmpty(),
            defaultModel = provider.defaultModel,
            active = activeProvider == provider,
            error = errors[provider],
        )
    }.toImmutableList()

    override fun onEvent(event: VoiceCommandsSettingsEvent) {
        when (event) {
            is VoiceCommandsSettingsEvent.OnInputChange -> {
                inputs[event.provider] = event.input
                errors.remove(event.provider)
            }

            is VoiceCommandsSettingsEvent.OnModelChange -> {
                models[event.provider] = event.model
                errors.remove(event.provider)
            }

            is VoiceCommandsSettingsEvent.OnSaveClick -> onSaveClick(event.provider)
            is VoiceCommandsSettingsEvent.OnRemoveClick -> onRemoveClick(event.provider)
            is VoiceCommandsSettingsEvent.OnUseClick -> onUseClick(event.provider)
        }
    }

    private suspend fun loadSettings() {
        apiKeysRepository.findActiveProvider().onRight { activeProvider = it }
        VoiceApiProvider.entries.forEach { provider ->
            either {
                apiKeysRepository.findKey(provider).bind()?.let { savedKeys[provider] = it }
                apiKeysRepository.findModel(provider).bind()?.let { models[provider] = it.value }
            }.onLeft { errors[provider] = it }
        }
    }

    private fun onSaveClick(provider: VoiceApiProvider) {
        viewModelScope.launch {
            either {
                val input = inputs[provider].orEmpty()
                val key = ensureNotNull(
                    if (input.isNotBlank()) NotBlankTrimmedString.from(input).bind() else savedKeys[provider]
                ) { "API key can't be empty" }
                apiKeysRepository.saveKey(provider, key).bind()
                apiKeysRepository.saveModel(
                    provider = provider,
                    model = NotBlankTrimmedString.from(models[provider].orEmpty()).getOrNull()
                ).bind()
                if (activeProvider == null) {
                    // the first configured provider is used by voice commands
                    apiKeysRepository.saveActiveProvider(provider).bind()
                    activeProvider = provider
                }
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
            either {
                apiKeysRepository.removeKey(provider).bind()
                if (activeProvider == provider) {
                    apiKeysRepository.saveActiveProvider(null).bind()
                    activeProvider = null
                }
            }.fold(
                ifLeft = { errors[provider] = it },
                ifRight = {
                    savedKeys.remove(provider)
                    errors.remove(provider)
                }
            )
        }
    }

    private fun onUseClick(provider: VoiceApiProvider) {
        if (savedKeys[provider] == null) return
        viewModelScope.launch {
            apiKeysRepository.saveActiveProvider(provider).fold(
                ifLeft = { errors[provider] = it },
                ifRight = { activeProvider = provider }
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
