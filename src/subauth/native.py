from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Any

from subauth.logging import REDACTED, redact_value


_PRIVATE_NATIVE_KEY = re.compile(
    r"^(?:cwd|workdir|home|workspace|instruction[_-]?sources|executable|log[_-]?uri|"
    r"(?:.*[_-])?paths?|current[_-]?dir|project[_-]?dir|email|user[_-]?id|"
    r"org(?:anization)?[_-]?id|"
    r"api[_-]?key[_-]?source|mcp[_-]?servers|plugins?|plugin[_-]?errors|"
    r"tool[_-]?info|tool[_-]?use[_-]?result|subagent[_-]?info|auth[_-]?url)$",
    re.IGNORECASE,
)


def sanitize_native_payload(value: Mapping[str, Any]) -> dict[str, Any]:
    """Return JSON-safe native metadata without credentials or local identity paths."""
    redacted = redact_value(value)
    sanitized = _sanitize_private_fields(redacted)
    if not isinstance(sanitized, dict):
        return {}
    event = sanitized.get("event")
    if isinstance(event, dict):
        method = event.get("method")
        if isinstance(method, str) and method.startswith("mcpServer/"):
            event["params"] = REDACTED
    return sanitized


def _sanitize_private_fields(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {
            str(key): (
                REDACTED
                if _PRIVATE_NATIVE_KEY.fullmatch(str(key))
                else _sanitize_private_fields(item)
            )
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [_sanitize_private_fields(item) for item in value]
    return value
