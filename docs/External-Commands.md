# External commands

Claude, Gemini and other apps can add and read Ivy Wallet transactions, e.g. _"add an expense of 25 for lunch from Cash"_ or _"how much did I spend on food this month?"_.

## Turn it on

**Settings → External commands** (`feature/external-commands`):

1. Turn on **Allow other apps to run commands** (off by default).
2. Copy the **access key**. Every command must send it; **New key** stops the old one from working.

## Connect Claude or Gemini

The assistant runs on your computer and reaches the phone through `adb`, with the MCP server in [`tools/ivy-mcp`](../tools/ivy-mcp/README.md) (Python 3.8+, no dependencies):

1. Install [Android platform-tools](https://developer.android.com/tools/releases/platform-tools), turn on USB or wireless debugging on the phone and check that `adb devices` lists it.
2. Add the server:
   - **Claude Code:** `claude mcp add ivy-wallet --env IVY_ACCESS_KEY=<key> -- python3 /path/to/tools/ivy-mcp/ivy_mcp.py`
   - **Claude Desktop** (`claude_desktop_config.json`) or **Gemini CLI** (`~/.gemini/settings.json`):
     ```json
     {
       "mcpServers": {
         "ivy-wallet": {
           "command": "python3",
           "args": ["/path/to/tools/ivy-mcp/ivy_mcp.py"],
           "env": { "IVY_ACCESS_KEY": "<key>" }
         }
       }
     }
     ```
   - Debug builds: add `"IVY_PACKAGE": "com.ivy.wallet.debug"`.
3. Ask the assistant.

The Claude and Gemini phone apps can't call apps on the phone, so they can't use this.

## The API

`IvyCommandsProvider` is an exported `ContentProvider` (`content://<application id>.commands`) that runs commands through `ContentProvider.call()`. Anything that can call a content provider can use it: `adb shell content call`, Tasker, another app.

```sh
adb shell content call --uri content://com.ivy.wallet.commands --method add_transaction \
  --arg '{"key":"<key>","amount":25,"account":"Cash","category":"Food","title":"Lunch"}'
# Result: Bundle[{json={"ok":true,"result":{"id":"…","type":"expense","amount":25.0,…}}}]
```

- **Method:** the command. **`--arg`:** a JSON object with the arguments and the `key`. Arguments can also be sent as `--extra name:s:value`.
- **Response:** the `json` string of the returned bundle, `{"ok":true,"result":…}` or `{"ok":false,"error":"…"}`. Errors say what to fix (e.g. the list of account names for an unknown account).

| Command | Arguments |
|---|---|
| `help` | none, no key needed |
| `list_accounts` | none; returns id, name, currency, balance |
| `list_categories`, `list_tags` | none |
| `add_transaction` | `type` (expense, income, transfer), `amount`, `account`, `currency`, `category`, `title`, `description`, `date`, `tags`, `to_account`, `to_amount` |
| `list_transactions` | `from`, `to`, `type`, `account`, `category`, `limit`; also returns the count and expense / income totals in the base currency |
| `delete_transaction` | `id` |

- Names of accounts, categories and tags are matched ignoring case; ids work too. Unknown names are errors, nothing is created.
- `account` can be left out when there's only one account.
- An amount in another `currency` is converted to the account's currency, and a transfer between currencies without `to_amount` is converted, with the app's exchange rates.
- `date`: `YYYY-MM-DD` (at the current time), `YYYY-MM-DDTHH:MM`, an ISO instant, `today` or `yesterday`. Default: now.

## Security

- Off by default; when off, or with a wrong key, every command except `help` is refused.
- The key is 128 random bits stored in the app's private storage (`ExternalCommandsSettings`, DataStore; the app has `allowBackup="false"`) and compared in constant time.
- Anyone with the key and a way to call the provider (adb access or an app on the phone) can read and change transactions. Use **New key** if it leaks.

## Code

- `ExternalCommandHandler`: checks the setting and the key, parses the arguments, returns the JSON response.
- `CommandProcessor` / `Command`: the commands, using the repositories (`TransactionRepository.save`, tag associations) and `CurrencyConverter` from `shared/domain`.
- Tests: `feature/external-commands/src/test`, `tools/ivy-mcp/test_ivy_mcp.py`.
