package com.ivy.voicecommands

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class VoiceCommandsSettingsState(
    val language: VoiceLanguage,
    val languages: ImmutableList<VoiceLanguage>,
    val apiKeys: ImmutableList<ApiKeyState>,
)

@Immutable
data class ApiKeyState(
    val provider: VoiceApiProvider,
    val title: String,
    val hasFreeTier: Boolean,
    val input: String,
    /** Masked hint of the saved key (e.g. "••••abcd") or null if no key is saved. */
    val savedKeyHint: String?,
    val model: String,
    val defaultModel: String,
    /** Whether voice commands use this provider. */
    val active: Boolean,
    val error: String?,
)
