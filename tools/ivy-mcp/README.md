# Ivy Wallet MCP server

Lets Claude, Gemini and other [MCP](https://modelcontextprotocol.io) clients manage Ivy Wallet (transactions, accounts, categories, tags, budgets, planned payments, loans, settings) on a phone connected with `adb`. Setup and the API: [docs/External-Commands.md](../../docs/External-Commands.md).

Tools: every command the app lists with `help` (see the table in the docs). When the phone can't be reached or runs an older Ivy Wallet, it offers the transaction tools: `list_accounts`, `list_categories`, `list_tags`, `add_transaction`, `list_transactions`, `delete_transaction`.

| Variable | |
|---|---|
| `IVY_ACCESS_KEY` | required, from Ivy Wallet: Settings → External commands |
| `IVY_PACKAGE` | default `com.ivy.wallet`; debug builds: `com.ivy.wallet.debug` |
| `ADB` | adb executable, default `adb` |
| `ANDROID_SERIAL` | the phone, when several are connected |

Try a command without an MCP client:

```sh
IVY_ACCESS_KEY=<key> python3 ivy_mcp.py call list_accounts
IVY_ACCESS_KEY=<key> python3 ivy_mcp.py call add_transaction '{"amount": 25, "account": "Cash", "title": "Lunch"}'
```

Tests: `python3 -m unittest test_ivy_mcp.py`
