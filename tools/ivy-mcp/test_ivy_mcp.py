"""Tests for ivy_mcp.py with a fake adb: python3 -m unittest test_ivy_mcp.py"""

import io
import json
import os
import stat
import tempfile
import unittest

import ivy_mcp


class IvyMcpTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.log = os.path.join(self.dir.name, "log")
        self.adb = os.path.join(self.dir.name, "adb")
        self.env = {"ADB": self.adb, "IVY_ACCESS_KEY": "secret"}

    def tearDown(self):
        self.dir.cleanup()

    def fake_adb(self, output):
        with open(self.adb, "w") as script:
            script.write("#!/bin/sh\nprintf '%s' \"$2\" > {}\ncat <<'EOF'\n{}\nEOF\n".format(self.log, output))
        os.chmod(self.adb, os.stat(self.adb).st_mode | stat.S_IEXEC)

    def adb_command(self):
        with open(self.log) as log:
            return log.read()

    def test_sends_the_command_with_the_key(self):
        self.fake_adb('Result: Bundle[{json={"ok":true,"result":{"id":"1"}}}]')

        response = ivy_mcp.call_ivy("add_transaction", {"amount": 25, "title": "Mama's lunch"}, self.env)

        self.assertEqual(response, {"ok": True, "result": {"id": "1"}})
        self.assertEqual(
            self.adb_command(),
            "content call --uri content://com.ivy.wallet.commands --method add_transaction --arg "
            "'{\"amount\": 25, \"title\": \"Mama'\\''s lunch\", \"key\": \"secret\"}'",
        )

    def test_debug_package(self):
        self.fake_adb('Result: Bundle[{json={"ok":true,"result":[]}}]')

        ivy_mcp.call_ivy("list_tags", {}, dict(self.env, IVY_PACKAGE="com.ivy.wallet.debug"))

        self.assertIn("content://com.ivy.wallet.debug.commands", self.adb_command())

    def test_app_not_reachable(self):
        self.fake_adb("Error while accessing provider:com.ivy.wallet.commands")

        response = ivy_mcp.call_ivy("list_tags", {}, self.env)

        self.assertFalse(response["ok"])
        self.assertIn("Error while accessing provider", response["error"])

    def test_missing_key(self):
        response = ivy_mcp.call_ivy("list_tags", {}, {"ADB": self.adb})

        self.assertFalse(response["ok"])
        self.assertIn("IVY_ACCESS_KEY", response["error"])

    def test_mcp_session(self):
        self.fake_adb('Result: Bundle[{json={"ok":false,"error":"Unknown account \'Wallet\'."}}]')
        requests = [
            {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-03-26"}},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
             "params": {"name": "add_transaction", "arguments": {"amount": 5, "account": "Wallet"}}},
            {"jsonrpc": "2.0", "id": 4, "method": "resources/list"},
        ]
        stdin = io.StringIO("\n".join(json.dumps(r) for r in requests) + "\n")
        stdout = io.StringIO()
        os.environ.update(self.env)

        ivy_mcp.serve(stdin, stdout)

        responses = [json.loads(line) for line in stdout.getvalue().splitlines()]
        self.assertEqual([r["id"] for r in responses], [1, 2, 3, 4])
        self.assertEqual(responses[0]["result"]["protocolVersion"], "2025-03-26")
        self.assertEqual(len(responses[1]["result"]["tools"]), 6)
        self.assertTrue(responses[2]["result"]["isError"])
        self.assertIn("Unknown account", responses[2]["result"]["content"][0]["text"])
        self.assertEqual(responses[3]["error"]["code"], -32601)

    def test_tools_from_the_app(self):
        help_result = [
            {"command": "help", "description": "Lists the commands.", "arguments": {},
             "parameters": {"type": "object", "properties": {}}},
            {"command": "add_budget", "description": "Adds a monthly budget.",
             "arguments": {"name": "required"},
             "parameters": {"type": "object", "properties": {"name": {"type": "string"}},
                            "required": ["name"]}},
        ]
        self.fake_adb("Result: Bundle[{json=" + json.dumps({"ok": True, "result": help_result}) + "}]")

        tools = ivy_mcp.list_tools(self.env)

        self.assertEqual([tool["name"] for tool in tools], ["add_budget"])
        self.assertEqual(tools[0]["inputSchema"]["required"], ["name"])
        self.assertIn("--method help", self.adb_command())

    def test_tools_fallback_for_older_apps(self):
        self.fake_adb('Result: Bundle[{json={"ok":true,"result":[{"command":"add_transaction"}]}}]')

        self.assertEqual(ivy_mcp.list_tools(self.env), ivy_mcp.TOOLS)

    def test_rejects_invalid_tool_names(self):
        response = ivy_mcp.handle(
            {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "rm -rf"}}, self.env
        )

        self.assertEqual(response["error"]["code"], -32602)


if __name__ == "__main__":
    unittest.main()
