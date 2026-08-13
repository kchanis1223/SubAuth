from __future__ import annotations

import json
import logging
import re
import sys
from collections.abc import Mapping, Sequence
from datetime import datetime, timezone
from typing import Any, TextIO


REDACTED = "[REDACTED]"
LOGGER_NAME = "subauth"

_SENSITIVE_KEY = re.compile(
    r"(?:api[_-]?key|auth[_-]?token|oauth[_-]?token|access[_-]?token|"
    r"refresh[_-]?token|password|secret|token)$",
    re.IGNORECASE,
)
_ASSIGNMENT = re.compile(
    r"(?i)(\b(?:[A-Z0-9_-]*(?:TOKEN|PASSWORD|SECRET|API[_-]?KEY))\b"
    r"\s*[:=]\s*)([^\s,;]+)"
)
_BEARER = re.compile(r"(?i)(\bBearer\s+)[A-Za-z0-9._~+\-/]+=*")


class StructuredFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        fields = getattr(record, "subauth_fields", {})
        payload: dict[str, Any] = {
            "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
            "level": record.levelname.lower(),
            "event": redact_text(record.getMessage()),
        }
        if isinstance(fields, Mapping):
            payload.update(redact_value(fields))
        if record.exc_info:
            payload["exception"] = redact_text(self.formatException(record.exc_info))
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def configure_structured_logging(stream: TextIO | None = None) -> logging.Logger:
    logger = logging.getLogger(LOGGER_NAME)
    logger.handlers.clear()
    handler = logging.StreamHandler(stream or sys.stdout)
    handler.setFormatter(StructuredFormatter())
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)
    logger.propagate = False
    return logger


def get_logger() -> logging.Logger:
    return logging.getLogger(LOGGER_NAME)


def log_event(
    logger: logging.Logger,
    level: int,
    event: str,
    **fields: Any,
) -> None:
    logger.log(level, event, extra={"subauth_fields": fields})


def redact_text(value: str, secrets: Sequence[str | None] = ()) -> str:
    redacted = value
    for secret in sorted(
        (secret for secret in secrets if secret and len(secret) >= 4),
        key=len,
        reverse=True,
    ):
        redacted = redacted.replace(secret, REDACTED)
    redacted = _ASSIGNMENT.sub(r"\1" + REDACTED, redacted)
    return _BEARER.sub(r"\1" + REDACTED, redacted)


def redact_value(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {
            str(key): REDACTED if _SENSITIVE_KEY.search(str(key)) else redact_value(item)
            for key, item in value.items()
        }
    if isinstance(value, (list, tuple)):
        return [redact_value(item) for item in value]
    if isinstance(value, str):
        return redact_text(value)
    return value
