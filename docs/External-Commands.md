# External commands

Claude, Gemini and other apps can do everything in Ivy Wallet: transactions, accounts, categories, tags, budgets, planned payments, loans and settings, e.g. _"add an expense of 25 for lunch from Cash"_, _"how much did I spend on food this month?"_ or _"create a 2,000 monthly budget for Car"_.

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

The Claude and Gemini phone apps can't call apps on the phone, so they can't use this. On the phone, use the [in-app assistant](Assistant.md).

## The API

`IvyCommandsProvider` is an exported `ContentProvider` (`content://<application id>.commands`) that runs commands through `ContentProvider.call()`. Anything that can call a content provider can use it: `adb shell content call`, Tasker, another app.

```sh
adb shell content call --uri content://com.ivy.wallet.commands --method add_transaction \
  --arg '{"key":"<key>","amount":25,"account":"Cash","category":"Food","title":"Lunch"}'
# Result: Bundle[{json={"ok":true,"result":{"id":"…","type":"expense","amount":25.0,…}}}]
```

- **Method:** the command. **`--arg`:** a JSON object with the arguments and the `key`. Arguments can also be sent as `--extra name:s:value`.
- **Response:** the `json` string of the returned bundle, `{"ok":true,"result":…}` or `{"ok":false,"error":"…"}`. Errors say what to fix (e.g. the list of account names for an unknown account).

`help` lists every command with its arguments and their JSON schema (`parameters`); the MCP server builds its tools from it.

| Area | Commands |
|---|---|
| Help | `help` (no key needed) |
| Accounts | `list_accounts` (id, name, currency, balance, include_in_balance), `add_account` (`name`, `currency`, `balance`, `include_in_balance`, `color`, `icon`), `update_account` (`account` + the same fields; a new `balance` is recorded as an "Adjust balance" transaction), `delete_account` (also deletes its transactions and planned payments) |
| Categories | `list_categories`, `add_category` (`name`, `color`, `icon`), `update_category`, `delete_category` (transactions stay, uncategorized) |
| Tags | `list_tags`, `add_tag` (`name`, `description`, `color`), `update_tag`, `delete_tag` |
| Transactions | `add_transaction` (`type` expense/income/transfer, `amount`, `account`, `currency`, `category`, `title`, `description`, `date`, `tags`, `to_account`, `to_amount`), `list_transactions` (`from`, `to`, `type`, `account`, `category`, `tag`, `status` paid/planned, `search`, `limit`; returns the count and paid expense / income totals in the base currency), `update_transaction` (`id` + any field to change), `delete_transaction` (`id`), `category_totals` (`from`, `to`, `type`, `account`) |
| Budgets | `list_budgets` (amount, spent, left for the current budget month), `add_budget` (`name`, `amount`, `categories`, `accounts`), `update_budget`, `delete_budget` |
| Planned payments | `list_planned_payments`, `add_planned_payment` (`type`, `amount`, `account`, `category`, `title`, `description`, `start_date`, `repeat` once/day/week/month/year, `every`), `update_planned_payment`, `delete_planned_payment`, `pay_transaction` (`id` of a planned transaction, `skip`) |
| Loans | `list_loans` (with paid / left and records), `add_loan` (`name`, `type` borrow/lend, `amount`, `account`, `note`, `date`), `update_loan`, `delete_loan`, `add_loan_record` (`loan`, `amount`, `type` decrease/increase, `account`, `note`, `date`, `interest`), `delete_loan_record` |
| Settings | `get_settings`, `update_settings` (`base_currency`, `start_day_of_month`), `list_exchange_rates`, `set_exchange_rate` (`currency`, `rate` or `price`), `delete_exchange_rate` |

- Names of accounts, categories, tags, budgets, loans and planned payments (by title) are matched ignoring case; ids work too. Unknown names are errors; nothing is created unless a command creates it.
- Only the given fields change in `update_*` commands. `"none"` clears a category, title, description or note; an empty list clears `tags`, `categories` or `accounts`.
- Budgets count paid expenses in the current budget month (from the start day of month setting) in their categories and accounts, in the base currency, like the Budgets screen.
- Planned payments create their upcoming transactions as planned (unpaid), like the app; `pay_transaction` pays one now or skips it.
- `account` can be left out when there's only one account.
- An amount in another `currency` is converted to the account's currency, and a transfer between currencies without `to_amount` is converted, with the app's exchange rates.
- `date`: `YYYY-MM-DD` (at the current time), `YYYY-MM-DDTHH:MM`, an ISO instant, `today` or `yesterday`. Default: now.

## Security

- Off by default; when off, or with a wrong key, every command except `help` is refused.
- The key is 128 random bits stored in the app's private storage (`ExternalCommandsSettings`, DataStore; the app has `allowBackup="false"`) and compared in constant time.
- Anyone with the key and a way to call the provider (adb access or an app on the phone) can read and change all the app's data, including deleting accounts. Use **New key** if it leaks.

## Code

- `ExternalCommandHandler`: checks the setting and the key, parses the arguments, returns the JSON response.
- `Command`: the commands with typed arguments (`parameters` is their JSON schema).
- `CommandProcessor`: runs each command with its `CommandGroup`: `TransactionCommands`, `AccountCommands`, `CategoryCommands` (and tags), `BudgetCommands`, `PlannedPaymentCommands`, `LoanCommands`, `SettingsCommands`. They use the repositories and DAOs directly, the same way the screens do, and `CurrencyConverter` / `SyncExchangeRatesUseCase` from `shared/domain`.
- Tests: `feature/external-commands/src/test`, `tools/ivy-mcp/test_ivy_mcp.py`.
