package com.ivy.voicecommands

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class VoiceCommandsSettingsState(
    val apiKeys: ImmutableList<ApiKeyState>,
)

@Immutable
data class ApiKeyState(
    val provider: VoiceApiProvider,
    val title: String,
    val input: String,
    /** Masked hint of the saved key (e.g. "••••abcd") or null if no key is saved. */
    val savedKeyHint: String?,
    val error: String?,
)
