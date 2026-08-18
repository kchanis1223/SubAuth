from __future__ import annotations

import json
import sys

signed_out = "--signed-out" in sys.argv
api_key = "--api-key" in sys.argv

for line in sys.stdin:
    message = json.loads(line)
    request_id = message.get("id")
    method = message.get("method")
    params = message.get("params") or {}
    if request_id is None:
        continue
    notifications = []
    if method == "initialize":
        result = {
            "codexHome": "/tmp/fake-codex",
            "platformFamily": "unix",
            "platformOs": "macos",
            "userAgent": "fake-codex/1.0",
        }
    elif method == "account/read":
        result = {
            "account": (
                None
                if signed_out
                else (
                    {"type": "apiKey"}
                    if api_key
                    else {
                        "type": "chatgpt",
                        "email": "redacted@example.test",
                        "planType": "plus",
                    }
                )
            ),
            "requiresOpenaiAuth": True,
        }
    elif method == "model/list":
        result = {
            "data": [
                {
                    "id": "test-model",
                    "model": "test-model",
                    "displayName": "Test Model",
                    "isDefault": True,
                    "inputModalities": ["text", "image"],
                }
            ],
            "nextCursor": None,
        }
    elif method == "account/login/start":
        result = {
            "type": "chatgpt",
            "loginId": "login-test",
            "authUrl": "https://example.test/login",
        }
    elif method == "thread/start":
        result = {
            "thread": {"id": "thread-test"},
            "model": "test-model",
        }
    elif method == "turn/interrupt":
        result = {}
    elif method == "turn/start":
        result = {"turn": {"id": "turn-test", "items": [], "status": "inProgress"}}
        thread_id = params["threadId"]
        notifications = [
            {
                "method": "turn/started",
                "params": {
                    "threadId": thread_id,
                    "turn": {
                        "id": "turn-test",
                        "items": [],
                        "status": "inProgress",
                    },
                },
            },
            {
                "method": "item/agentMessage/delta",
                "params": {
                    "threadId": thread_id,
                    "turnId": "turn-test",
                    "itemId": "item-test",
                    "delta": "O",
                },
            },
            {
                "method": "item/agentMessage/delta",
                "params": {
                    "threadId": thread_id,
                    "turnId": "turn-test",
                    "itemId": "item-test",
                    "delta": "K",
                },
            },
            {
                "method": "turn/completed",
                "params": {
                    "threadId": thread_id,
                    "turn": {
                        "id": "turn-test",
                        "items": [],
                        "status": "completed",
                        "error": None,
                    },
                },
            },
        ]
    else:
        print(
            json.dumps(
                {"id": request_id, "error": {"code": -32601, "message": method}}
            ),
            flush=True,
        )
        continue
    print(json.dumps({"id": request_id, "result": result}), flush=True)
    for notification in notifications:
        print(json.dumps(notification), flush=True)
