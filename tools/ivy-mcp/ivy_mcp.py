#!/usr/bin/env python3
"""Ivy Wallet MCP server.

Lets Claude, Gemini and other MCP clients run Ivy Wallet commands on an Android
phone connected with adb (USB or wireless debugging). Needs only Python 3.8+ and adb.

Environment:
  IVY_ACCESS_KEY  required, from Ivy Wallet: Settings -> External commands
  IVY_PACKAGE     app package, default com.ivy.wallet (debug builds: com.ivy.wallet.debug)
  ADB             adb executable, default "adb"
  ANDROID_SERIAL  phone to use when several are connected (read by adb)

Usage:
  ivy_mcp.py                         run the MCP server over stdio
  ivy_mcp.py call METHOD [JSON]      run one command and print the response
"""

import json
import os
import re
import subprocess
import sys

SERVER_NAME = "ivy-wallet"
SERVER_VERSION = "1.1.0"
DEFAULT_PROTOCOL_VERSION = "2025-06-18"
ADB_TIMEOUT_SECONDS = 30

_NAME_HINT = "Use the exact name from list_accounts / list_categories / list_tags, or the id."

TOOLS = [
    {
        "name": "list_accounts",
        "description": "Lists the Ivy Wallet accounts with their currency and balance.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "list_categories",
        "description": "Lists the Ivy Wallet categories.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "list_tags",
        "description": "Lists the Ivy Wallet tags.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "add_transaction",
        "description": (
            "Adds an expense, an income or a transfer between two accounts to Ivy Wallet. "
            "The amount is in the account's currency unless 'currency' says otherwise. "
            "Call list_accounts and list_categories first to use the right names."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "type": {"type": "string", "enum": ["expense", "income", "transfer"],
                         "description": "Default: expense."},
                "amount": {"type": "number", "exclusiveMinimum": 0},
                "account": {"type": "string",
                            "description": "The paying (or receiving, for income) account. " + _NAME_HINT},
                "currency": {"type": "string",
                             "description": "Currency code of the amount when it isn't the account's; "
                                            "it's converted with the app's exchange rates."},
                "category": {"type": "string", "description": _NAME_HINT},
                "title": {"type": "string", "description": "Short title, e.g. 'Lunch'."},
                "description": {"type": "string"},
                "date": {"type": "string",
                         "description": "YYYY-MM-DD, YYYY-MM-DDTHH:MM, 'today' or 'yesterday'. Default: now."},
                "tags": {"type": "array", "items": {"type": "string"}, "description": _NAME_HINT},
                "to_account": {"type": "string", "description": "Transfers: the receiving account. " + _NAME_HINT},
                "to_amount": {"type": "number", "exclusiveMinimum": 0,
                              "description": "Transfers between currencies: the received amount. "
                                             "Converted with the app's exchange rates when missing."},
            },
            "required": ["amount"],
        },
    },
    {
        "name": "list_transactions",
        "description": (
            "Lists Ivy Wallet transactions, newest first, with the count and the expense and income "
            "totals (in the base currency) of all matching transactions."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "from": {"type": "string", "description": "First date, YYYY-MM-DD."},
                "to": {"type": "string", "description": "Last date, YYYY-MM-DD or 'today'."},
                "type": {"type": "string", "enum": ["expense", "income", "transfer"]},
                "account": {"type": "string", "description": _NAME_HINT},
                "category": {"type": "string", "description": _NAME_HINT},
                "limit": {"type": "integer", "minimum": 1, "maximum": 200, "description": "Default: 20."},
            },
        },
    },
    {
        "name": "delete_transaction",
        "description": "Deletes an Ivy Wallet transaction by id (from add_transaction or list_transactions).",
        "inputSchema": {
            "type": "object",
            "properties": {"id": {"type": "string"}},
            "required": ["id"],
        },
    },
]

_TOOL_NAME = re.compile(r"^[a-z][a-z_]*$")
_RESULT = re.compile(r"Result: Bundle\[\{json=(.*)\}\]\s*$", re.DOTALL)


def shell_quote(text):
    """Quotes text for the phone's shell."""
    return "'" + text.replace("'", "'\\''") + "'"


def call_ivy(method, arguments, env=None):
    """Runs a command in Ivy Wallet and returns its JSON response as a dict."""
    env = os.environ if env is None else env
    key = env.get("IVY_ACCESS_KEY", "").strip()
    if not key and method != "help":
        return {"ok": False, "error": "IVY_ACCESS_KEY isn't set. Copy the key from Ivy Wallet: "
                                      "Settings -> External commands."}
    package = env.get("IVY_PACKAGE", "com.ivy.wallet").strip()
    payload = dict(arguments or {})
    payload["key"] = key
    command = "content call --uri content://{}.commands --method {} --arg {}".format(
        package, method, shell_quote(json.dumps(payload, ensure_ascii=False))
    )
    try:
        process = subprocess.run(
            [env.get("ADB", "adb"), "shell", command],
            capture_output=True, text=True, timeout=ADB_TIMEOUT_SECONDS, encoding="utf-8",
        )
    except FileNotFoundError:
        return {"ok": False, "error": "adb not found. Install Android platform-tools or set ADB."}
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "adb timed out. Is the phone connected and unlocked?"}
    output = (process.stdout or "").strip()
    match = _RESULT.search(output)
    if match is None:
        details = (output + "\n" + (process.stderr or "")).strip()
        return {"ok": False, "error": "Ivy Wallet didn't answer. Check that the phone is connected "
                                      "(adb devices), Ivy Wallet is installed as {} and external commands "
                                      "are on. adb said: {}".format(package, details or "nothing")}
    try:
        return json.loads(match.group(1))
    except ValueError:
        return {"ok": False, "error": "Unexpected response: " + match.group(1)}


def list_tools(env=None):
    """The app's commands as MCP tools, from its help command.

    Falls back to the built-in TOOLS (transactions only) when the phone can't be reached or runs an
    older Ivy Wallet.
    """
    response = call_ivy("help", {}, env)
    commands = response.get("result") if response.get("ok") else None
    if not isinstance(commands, list):
        return TOOLS
    tools = []
    for command in commands:
        name = command.get("command") if isinstance(command, dict) else None
        if not name or name == "help" or "parameters" not in command:
            continue
        tools.append({
            "name": name,
            "description": command.get("description", ""),
            "inputSchema": command.get("parameters") or {"type": "object", "properties": {}},
        })
    return tools or TOOLS


def _response(request_id, result=None, error=None):
    message = {"jsonrpc": "2.0", "id": request_id}
    if error is not None:
        message["error"] = error
    else:
        message["result"] = result
    return message


def handle(message, env=None):
    """Handles one JSON-RPC message, returns the response or None for notifications."""
    method = message.get("method")
    request_id = message.get("id")
    if request_id is None:
        return None
    params = message.get("params") or {}
    if method == "initialize":
        return _response(request_id, {
            "protocolVersion": params.get("protocolVersion") or DEFAULT_PROTOCOL_VERSION,
            "capabilities": {"tools": {}},
            "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            "instructions": "Ivy Wallet is a money manager on the user's phone. The tools cover "
                            "everything the app does: transactions, accounts, categories, tags, budgets, "
                            "planned payments, loans and settings. Look up names with the list tools "
                            "before using them, and confirm with the user before deleting anything.",
        })
    if method == "ping":
        return _response(request_id, {})
    if method == "tools/list":
        return _response(request_id, {"tools": list_tools(env)})
    if method == "tools/call":
        name = params.get("name")
        if not isinstance(name, str) or not _TOOL_NAME.match(name) or name == "help":
            return _response(request_id, error={"code": -32602, "message": "Unknown tool: {}".format(name)})
        response = call_ivy(name, params.get("arguments") or {}, env)
        ok = bool(response.get("ok"))
        content = response.get("result") if ok else response.get("error")
        return _response(request_id, {
            "content": [{"type": "text", "text": json.dumps(content, ensure_ascii=False, indent=2)}],
            "isError": not ok,
        })
    return _response(request_id, error={"code": -32601, "message": "Method not found: {}".format(method)})


def serve(stdin=sys.stdin, stdout=sys.stdout):
    for line in stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except ValueError:
            response = _response(None, error={"code": -32700, "message": "Parse error"})
        else:
            response = handle(message) if isinstance(message, dict) else None
        if response is not None:
            stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
            stdout.flush()


def main(argv):
    if len(argv) >= 2 and argv[0] == "call":
        arguments = json.loads(argv[2]) if len(argv) > 2 else {}
        response = call_ivy(argv[1], arguments)
        print(json.dumps(response, ensure_ascii=False, indent=2))
        return 0 if response.get("ok") else 1
    if argv:
        print(__doc__)
        return 2
    serve()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
