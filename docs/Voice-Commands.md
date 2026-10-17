# Voice Commands

Add a transaction by speaking it, e.g. _"spent 25 on coffee"_, _"received salary 3000"_, _"transfer 100 from Cash to Bank"_.

## Approach

The feature has two independent parts:

1. **Speech → text** (device IO)
2. **Text → transaction draft** (pure, unit-testable logic)

### Speech → text

Use `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` via `rememberLauncherForActivityResult(StartActivityForResult())`.

- No new dependency, works on min SDK 28.
- No `RECORD_AUDIO` permission - the system recognizer owns the mic and its UI.
- `EXTRA_PREFER_OFFLINE = true` keeps recognition on-device where supported.
- Hide the entry point when no recognizer is available (`SpeechRecognizer.isRecognitionAvailable`, `ActivityNotFoundException`).
- Ignore `RESULT_CANCELED` and empty `EXTRA_RESULTS`.

Rejected: recording audio and sending it to a cloud STT/LLM. Ivy Wallet is offline-first without a backend - that would require API keys, network and sending financial data off-device.

### Text → transaction draft

A local rule-based `VoiceTransactionParser` that returns `Either<VoiceParseError, VoiceTransactionDraft>`:

- **Amount:** first number token (`25`, `25.50`, `25,50`, `$25`, `25 dollars`) → `PositiveDouble`, otherwise `Left(NoAmount)`.
- **Type:** keyword sets - expense (spent, paid, bought), income (received, salary, earned), transfer (transfer, "from X to Y"). Defaults to expense.
- **Category / account:** case-insensitive match against `CategoryRepository.findAll()` and `AccountRepository.findAll()` names.
- **Date:** "today", "yesterday", weekday names; otherwise `null` (defaults to now).
- **Title:** the remaining text after removing amount, keywords and matched names.

```kotlin
sealed interface VoiceTransactionDraft {
    val amount: PositiveDouble
    val title: NotBlankTrimmedString?
    val time: Instant?

    data class Expense(..., val account: AccountId?, val category: CategoryId?) : VoiceTransactionDraft
    data class Income(..., val account: AccountId?, val category: CategoryId?) : VoiceTransactionDraft
    data class Transfer(..., val from: AccountId?, val to: AccountId?) : VoiceTransactionDraft
}
```

Keep the matching logic a pure function of `(text, categories, accounts, now)` so it's tested without fakes.

### API keys

Cloud providers (speech-to-text or LLM parsing) need the user's own API key. **Settings → Voice commands** (`feature/voice-commands`) lets the user enter, replace and delete a key per provider (`VoiceApiProvider`: OpenAI, Anthropic, Google).

- Keys are stored in `EncryptedSharedPreferences` (AES-256, Android Keystore-backed) by `VoiceApiKeysRepository`; the app has `allowBackup="false"`, so they never leave the device.
- The screen never displays a saved key - only a masked hint (`••••abcd`).
- `VoiceApiKeysRepository.findKey(provider)` is the read API for the voice command pipeline.

## Flow

Voice recognition is error-prone, so the draft **pre-fills the existing editor** instead of saving directly. This reuses the validation, loans, exchange rates and widget refresh logic in `EditTransactionViewModel.saveInternal`.

1. Mic button in the expanded FAB menu (`feature/main/.../MainBottomBar.kt`), passed down like `onAddExpense`.
2. `MainScreen` launches the recognizer and sends the text to its VM.
3. VM calls `VoiceTransactionParser.parse(text)`: `Left` → toast, `Right` → navigate.
4. Extend `EditTransactionScreen` (`shared/ui/navigation/.../Screens.kt`) with optional `amount`, `title`, `dateTime`, `toAccountId` extras (next to the existing `accountId` / `categoryId`).
5. `EditTransactionViewModel.start()` applies them for new transactions and sets `hasChanges = true`.

Optional: a "Voice" launcher shortcut in `app/src/main/res/xml/shortcuts.xml`.

## Module

```
feature/voice-transaction/            namespace com.ivy.voicetransaction
  VoiceTransactionDraft.kt
  VoiceParseError.kt                  sealed: NoAmount, Blank
  VoiceTransactionParser.kt
  VoiceInputButton.kt                 launcher + availability check
  test/VoiceTransactionParserTest.kt
  test/VoiceInputButtonPaparazziTest.kt
```

- `settings.gradle.kts`: `include(":feature:voice-transaction")`
- `feature/main/build.gradle.kts`: `implementation(projects.feature.voiceTransaction)`

No new `Screen` or nav graph entry - the flow ends in `EditTransactionScreen`.

## Parser test cases

- "spent 25 on coffee" → Expense 25, title "coffee"
- "received salary 3000" → Income 3000
- "transfer 100 from Cash to Bank" → Transfer Cash → Bank
- "paid 12.50 for taxi yesterday" → time = yesterday
- "25,50 groceries" → 25.5
- "coffee" → `Left(NoAmount)`; "" → `Left(Blank)`
- Category matching is case-insensitive

## Limitations

- Type/date keywords are English-only; other languages need per-locale keyword sets.
- Recognition quality and offline support depend on the device's speech service.
