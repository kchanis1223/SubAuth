from __future__ import annotations

import json
import os
import sys

args = sys.argv[1:]

if args == ["--version"]:
    print("1.1.999")
    raise SystemExit(0)

if args == ["models"]:
    if os.environ.get("FAKE_AGY_SIGNED_OUT"):
        print("authentication required", file=sys.stderr)
        raise SystemExit(1)
    if os.environ.get("GEMINI_API_KEY"):
        print("api-key-model  API billed model")
        raise SystemExit(0)
    print("gemini-test-high  Gemini Test (High)")
    print("gemini-test-fast  Gemini Test (Fast)")
    print("claude-test       Claude Test")
    raise SystemExit(0)

if "-p" in args and "stream-json" in args:
    required_flags = {
        "--model",
        "--output-format",
        "--print-timeout",
        "--sandbox",
        "--disable-slash-commands",
    }
    if not required_flags.issubset(args):
        print("missing isolation or streaming flags", file=sys.stderr)
        raise SystemExit(2)
    conversation_id = "conversation-test"
    messages = [
        {
            "event": "init",
            "conversation_id": conversation_id,
            "init": {
                "cwd": "/temporary/workspace",
                "tools": ["run_command", "write_to_file"],
                "permission_mode": "request-review",
                "model": "gemini-test-high",
            },
        },
        {
            "event": "step_update",
            "step_update": {
                "conversation_id": conversation_id,
                "step_index": 0,
                "state": "DONE",
                "step_type": "user_input",
            },
        },
    ]
    if os.environ.get("FAKE_AGY_TOOL"):
        messages.append(
            {
                "event": "step_update",
                "step_update": {
                    "conversation_id": conversation_id,
                    "step_index": 1,
                    "state": "ACTIVE",
                    "step_type": "tool",
                    "tool_name": "run_command",
                },
            }
        )
    else:
        messages.extend(
            [
                {
                    "event": "step_update",
                    "step_update": {
                        "conversation_id": conversation_id,
                        "step_index": 1,
                        "state": "ACTIVE",
                        "step_type": "agent_response",
                        "text_delta": "GEMINI",
                    },
                },
                {
                    "event": "step_update",
                    "step_update": {
                        "conversation_id": conversation_id,
                        "step_index": 1,
                        "state": "DONE",
                        "step_type": "agent_response",
                        "text_delta": "_OK",
                    },
                },
                {
                    "event": "result",
                    "result": {
                        "conversation_id": conversation_id,
                        "status": "SUCCESS",
                        "response": "GEMINI_OK",
                        "usage": {
                            "input_tokens": 4,
                            "output_tokens": 3,
                            "thinking_tokens": 0,
                            "cache_read_tokens": 0,
                            "total_tokens": 7,
                        },
                    },
                },
            ]
        )
    for message in messages:
        print(json.dumps(message), flush=True)
    raise SystemExit(0)

print(f"unsupported fake Antigravity invocation: {args}", file=sys.stderr)
raise SystemExit(2)
