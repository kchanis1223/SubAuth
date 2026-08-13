from __future__ import annotations

import json
import os
import sys

args = sys.argv[1:]

if args == ["--version"]:
    print("2.1.999 (Claude Code)")
    raise SystemExit(0)

if args[:3] == ["auth", "status", "--json"]:
    if os.environ.get("ANTHROPIC_API_KEY"):
        value = {
            "loggedIn": True,
            "authMethod": "apiKey",
            "apiProvider": "firstParty",
            "subscriptionType": None,
        }
    elif "--signed-out" in args:
        value = {
            "loggedIn": False,
            "authMethod": "none",
            "apiProvider": "firstParty",
            "subscriptionType": None,
        }
    elif os.environ.get("CLAUDE_CODE_OAUTH_TOKEN"):
        value = {
            "loggedIn": True,
            "authMethod": "oauth_token",
            "subscriptionType": None,
            "email": "must-not-leak@example.test",
            "orgId": "must-not-leak",
        }
    else:
        value = {
            "loggedIn": True,
            "authMethod": "claude.ai",
            "apiProvider": "firstParty",
            "email": "must-not-leak@example.test",
            "orgId": "must-not-leak",
            "subscriptionType": "max",
        }
    print(json.dumps(value))
    raise SystemExit(0 if value["loggedIn"] else 1)

if args[:3] == ["auth", "login", "--claudeai"]:
    raise SystemExit(0)

if "-p" in args and "stream-json" in args:
    required_flags = {
        "--verbose",
        "--include-partial-messages",
        "--no-session-persistence",
        "--safe-mode",
        "--disable-slash-commands",
        "--strict-mcp-config",
        "--tools",
    }
    if not required_flags.issubset(args):
        print("missing isolation or streaming flags", file=sys.stderr)
        raise SystemExit(2)
    tools_index = args.index("--tools")
    if args[tools_index + 1] != "":
        print("tools were not disabled", file=sys.stderr)
        raise SystemExit(2)
    messages = [
        {
            "type": "system",
            "subtype": "init",
            "session_id": "session-test",
            "model": "claude-test",
            "tools": [],
        },
        {
            "type": "stream_event",
            "event": {
                "type": "content_block_delta",
                "delta": {"type": "text_delta", "text": "CLAUDE"},
            },
        },
        {
            "type": "stream_event",
            "event": {
                "type": "content_block_delta",
                "delta": {"type": "text_delta", "text": "_OK"},
            },
        },
        {
            "type": "result",
            "subtype": "success",
            "is_error": False,
            "session_id": "session-test",
            "result": "CLAUDE_OK",
            "usage": {"input_tokens": 2, "output_tokens": 3},
        },
    ]
    for message in messages:
        print(json.dumps(message), flush=True)
    raise SystemExit(0)

print(f"unsupported fake Claude invocation: {args}", file=sys.stderr)
raise SystemExit(2)
