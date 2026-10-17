# Voice Commands

Add a transaction by speaking it, e.g. _"spent 25 on coffee"_, _"received salary 3000"_, _"transfer 100 from Cash to Bank"_.

## Flow

1. **"+" → "Add by voice"** (`feature/main/.../MainBottomBar.kt`) starts the voice command.
2. **Speech → text:** `VoiceTransactionHost` launches the device's speech recognizer (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`). Free, no `RECORD_AUDIO` permission - the system recognizer owns the mic. The language comes from **Settings → Voice commands → Voice language** (`VoiceLanguage`: phone language, English, Arabic (Egypt), Arabic (Saudi Arabia)) via `EXTRA_LANGUAGE`.
3. **Text → drafts:** `VoiceTransactionParser` turns the text into one `VoiceTransactionDraft` per transaction mentioned ("spent 50 on food and 20 on coffee, then transferred 500 from Cash to Bank"):
   - with an AI provider configured → `LlmVoiceTransactionParser` (any language)
   - without one, or if the AI request fails → `LocalVoiceTransactionParser` (offline, English and Arabic)
4. **Drafts → confirmation** - nothing is saved automatically:
   - one transaction: `EditTransactionScreen` opens pre-filled (`amount`, `title`, `dateTime`, `accountId`, `toAccountId`, `categoryId`) and its existing save logic is reused.
   - several transactions: `VoiceTransactionsReviewScreen` (`review/`) lists them as a checklist. Each card shows the type, date, amount and account(s); all of them can be edited (date picker, account dropdowns, the received amount for transfers between currencies). **Save** stores the checked ones with `VoiceTransactionsSaver` (`TransactionRepository.saveMany` + balance widget refresh). Transfers between currencies are converted with the app's exchange rates (`CurrencyConverter`) and the received amount can be edited.

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

## Several transactions in one command

- **Offline:** the text is split at "and", "then", "also", commas, sentence ends, "و", "ثم", "وبعدين" and "و" attached to a verb ("وصرفت"). Parts without an amount stay with the previous transaction ("on food and drinks"). A transaction that doesn't say its type, date or account takes them from the one before ("spent 50 on food from cash yesterday and 20 on coffee").
- **AI:** the model returns `{"transactions": [...]}`; a single object is accepted too, and items without an amount are dropped.

## Offline parser

`LocalVoiceTransactionParser` (rule-based, English and Arabic):

- **Amount:** first number - `25`, `25.50`, `25,50`, `1,500.50`, Arabic-Indic digits (`٥٠`, `٢٥٫٥`). Numbers said as words aren't supported - speech recognizers return digits.
- **Type:** transfer (`transfer`, `moved`, "from X to Y" with two accounts), income (`received`, `salary`, `earned`, `got paid`, ...), otherwise expense.
- **Accounts:** "from / using / with X" is the paying account, "to / into Y" the receiving one ("transfer 100 to savings" fills only the receiving account).
- **Category / account matching** (`NameMatcher`): word-based and tolerant of how speech recognition writes names - case, emojis and punctuation are ignored, plurals match ("savings" ~ "Saving"), small misspellings of longer words match ("vodaphone" ~ "Vodafone"), spelled-out letters match acronyms ("c i b" ~ "CIB") and part of a name matches ("misr" ~ "Bank Misr") unless another name matches equally well ("bank" with "Bank Misr" and "Bank Alahly" selects nothing). The longest matching category wins.
- **Arabic:** keywords (`صرفت`, `دفعت`, `حولت`, `حطيت`, `استلمت`, `قبضت`, `مرتب`, ...), `من` / `إلى`, `على`, `في`, `لـ`, dates (`امبارح`, `أول امبارح`, `من يومين`, `من 3 أيام`, `يوم الخميس`). `ArabicText` unifies spelling variants (أ/إ/آ, ة/ه, ى/ي), drops the article and attached prefixes (`بالكاش` → `كاش`, `لبنك` → `بنك`) and compares Arabic words with English names by sound, so English account names can be said in Arabic (`بنك مصر` ~ Bank Misr, `كاش` ~ Cash, `فودافون` ~ Vodafone, `فيزا` ~ Visa). Names that are translated rather than transliterated (`التوفير` for "Savings") need the AI parser or an Arabic account name.
- **Date:** `today`, `yesterday`, `day before yesterday`, `N days ago`, weekday names (the last one).
- **Title:** what's left after removing amounts, keywords, account names and dates.

## Errors

- No speech recognizer on the device → toast.
- No amount found → toast with an example.
- AI request fails → falls back to the offline parser and shows the AI error; if the offline parser fails too, only the AI error is shown.

## Tests

`feature/voice-commands/src/test`: offline parser, LLM response mapping and prompt, AI/offline fallback, voice command and settings view-models.

## Limitations

- The offline parser understands English and Arabic; other languages need an AI provider.
- Recognition quality and offline support depend on the device's speech service.
- Free tiers are rate-limited and Google may use free-tier prompts to improve its products.
