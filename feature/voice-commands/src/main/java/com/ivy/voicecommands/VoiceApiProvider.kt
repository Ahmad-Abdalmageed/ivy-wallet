package com.ivy.voicecommands

/**
 * AI providers that can turn a voice command into a transaction.
 * [defaultModel] is used when the user doesn't set a model of their own.
 */
enum class VoiceApiProvider(
    val displayName: String,
    val defaultModel: String,
    val hasFreeTier: Boolean,
) {
    Google(
        displayName = "Google Gemini",
        defaultModel = "gemini-flash-latest",
        hasFreeTier = true,
    ),
    Groq(
        displayName = "Groq",
        defaultModel = "openai/gpt-oss-20b",
        hasFreeTier = true,
    ),
    OpenAI(
        displayName = "OpenAI",
        defaultModel = "gpt-5.4-mini",
        hasFreeTier = false,
    ),
    Anthropic(
        displayName = "Anthropic Claude",
        defaultModel = "claude-opus-5-5",
        hasFreeTier = false,
    ),
}
