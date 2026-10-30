package com.ivy.commands.ui

import androidx.compose.runtime.Immutable

@Immutable
data class ExternalCommandsState(
    val enabled: Boolean,
    val accessKey: String,
    val keyVisible: Boolean,
    val authority: String,
    val exampleCommand: String,
)
