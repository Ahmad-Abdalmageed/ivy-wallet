# Voice Commands

Add a transaction by speaking it, e.g. _"spent 25 on coffee"_, _"received salary 3000"_, _"transfer 100 from Cash to Bank"_.

## Flow

1. **"+" → "Add by voice"** (`feature/main/.../MainBottomBar.kt`) starts the voice command.
2. **Speech → text:** `VoiceTransactionHost` launches the device's speech recognizer (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`). Free, no `RECORD_AUDIO` permission - the system recognizer owns the mic.
3. **Text → draft:** `VoiceTransactionParser` turns the text into a `VoiceTransactionDraft`:
   - with an AI provider configured → `LlmVoiceTransactionParser` (any language)
   - without one, or if the AI request fails → `LocalVoiceTransactionParser` (offline, English)
4. **Draft → confirmation:** `EditTransactionScreen` opens pre-filled (`amount`, `title`, `dateTime`, `accountId`, `toAccountId`, `categoryId`). The user reviews and saves - nothing is saved automatically, and the existing save logic (validation, loans, exchange rates, widgets) is reused.

## AI providers

Configured in **Settings → Voice commands** (`VoiceCommandsSettingsScreen`): an API key and an optional model per provider, and which provider is in use.

| Provider | Default model | Free tier | API |
|---|---|---|---|
| Google Gemini | `gemini-flash-latest` | Yes | `generateContent`, `responseMimeType: application/json` |
| Groq | `openai/gpt-oss-20b` | Yes | OpenAI-compatible Chat Completions, JSON mode |
| OpenAI | `gpt-5.4-mini` | No | Chat Completions, JSON mode |
| Anthropic Claude | `claude-opus-5-5` | No | Messages API, structured outputs (`output_config.format`), `effort: low`, server-side refusal fallbacks |

- Model IDs change often, so the model is editable; a blank model uses the default.
- `VoiceApiKeysRepository` stores keys, models and the active provider in `EncryptedSharedPreferences` (Android Keystore). The app has `allowBackup="false"`, so keys never leave the device. A saved key is only shown masked (`••••abcd`).
- `LlmClient` calls the providers with the app's Ktor client. The API key goes in a header, never in the URL, so it isn't logged.
- The prompt includes the user's category and account names; the response is mapped back to IDs with `NameMatcher` (case- and diacritics-insensitive). Unknown names, future dates and invalid types are dropped.

## Offline parser

`LocalVoiceTransactionParser` (rule-based, English):

- **Amount:** first number - `25`, `25.50`, `25,50`, `1,500.50`.
- **Type:** transfer (`transfer`, `moved`, "from X to Y" with two accounts), income (`received`, `salary`, `earned`, `got paid`, ...), otherwise expense.
- **Category / account:** names mentioned in the text; the longest matching category wins.
- **Date:** `today`, `yesterday`, `day before yesterday`, `N days ago`, weekday names (the last one).
- **Title:** what's left after removing amounts, keywords, account names and dates.

## Errors

- No speech recognizer on the device → toast.
- No amount found → toast with an example.
- AI request fails → falls back to the offline parser and shows the AI error; if the offline parser fails too, only the AI error is shown.

## Tests

`feature/voice-commands/src/test`: offline parser, LLM response mapping and prompt, AI/offline fallback, voice command and settings view-models.

## Limitations

- The offline parser understands English only; other languages need an AI provider.
- Recognition quality and offline support depend on the device's speech service.
- Free tiers are rate-limited and Google may use free-tier prompts to improve its products.
