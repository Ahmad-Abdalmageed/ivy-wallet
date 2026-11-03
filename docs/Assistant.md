# In-app assistant

Tell Ivy Wallet what you spent, received or moved, or ask how much you spent, in your own words, by typing or speaking, on the phone: _"spent 120 on groceries from Cash and 30 on a taxi yesterday"_, _"how much did I spend on food this month?"_.

Gemini on Android can't call apps like Ivy Wallet yet (Android AppFunctions is a private preview), so the assistant runs inside the app.

## Open it

- **Home → More → Assistant.**
- **Long-press the Ivy Wallet icon → Ask Ivy** (or drag that shortcut to the home screen). Starts listening right away.
- **As the phone's assistant app:** the app answers `android.intent.action.ASSIST`, so where Android lists it under **Settings → Apps → Default apps → Digital assistant app**, choosing it opens the assistant with the assistant gesture. This replaces Gemini as the assistant.

## Set up

The assistant uses Google Gemini with your own API key. Gemini's free tier is enough.

1. Get a key at [aistudio.google.com/apikey](https://aistudio.google.com/apikey).
2. Paste it in the assistant (it asks the first time, or the settings button at the top). The model is editable; empty uses `gemini-flash-latest`.

The key is stored in the app's private storage (`AssistantSettings`, DataStore; the app has `allowBackup="false"`) and sent only to Google in a header. Your messages and your account, category and tag names are sent to Google with each request.

## What it does

- Adds expenses, income and transfers (several per message), with account, category, tags, date and currency conversion; each one appears as a card with **Undo**.
- Reads receipt photos: tap the camera button to take a photo or pick one from the gallery, add a note if you like ("from Ahly EGP", "split by item"), and send. By default it adds one expense for the total, titled with the merchant, on the receipt's date and in its currency.
- Answers questions about your transactions using `list_transactions` and its totals.
- Deletes transactions when you ask.
- Asks which account when it isn't clear and there's more than one. Never creates accounts, categories or tags.
- Replies in your language; voice input uses the phone's speech recognition in the phone's language.

## Code

`feature/external-commands`, package `com.ivy.commands.assistant`:

- `AssistantAgent`: the conversation with Gemini. Each message runs a loop: `generateContent` with the commands as function declarations (`AssistantTools`), run the function calls with the same `CommandProcessor` as the [external commands](External-Commands.md) (without the access key, since it's the app itself), send the results back, until Gemini answers with text (at most 10 steps). The model's replies are kept as is, including thought signatures. A failed request is rolled back so the conversation stays valid.
- `GeminiClient`: `POST /v1beta/models/{model}:generateContent` with the app's Ktor client and a 90 s timeout.
- `ReceiptImages`: camera files through a `FileProvider` (`<application id>.assistant.files`, cache `receipts/`; no camera permission needed) and photos shrunk to a JPEG of at most 1600 px, sent to Gemini as `inlineData` with the message. Gallery photos come from the system photo picker, so no storage permission either.
- `AssistantViewModel` / `AssistantScreen`: Material 3 chat with voice input, transaction cards with undo and the key settings dialog.
- Entry points: `AssistantScreen` in `shared/ui/navigation`, the `ivy.wallet.intent.action.assistant` / `ACTION_ASSIST` intent filter and `RootViewModel.handleSpecialStart`, the `sc_assistant` launcher shortcut.
- Tests: `feature/external-commands/src/test/java/com/ivy/commands/assistant`.
