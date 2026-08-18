from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from typing import Any, Mapping

PROTOCOL_VERSION = "1"


@dataclass(frozen=True, slots=True)
class Request:
    id: str
    method: str
    params: Mapping[str, Any] = field(default_factory=dict)
    protocol: str = PROTOCOL_VERSION

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> Request:
        return cls(
            id=str(value["id"]),
            method=str(value["method"]),
            params=dict(value.get("params") or {}),
            protocol=str(value.get("protocol", PROTOCOL_VERSION)),
        )


@dataclass(frozen=True, slots=True)
class Response:
    id: str
    result: Mapping[str, Any] | None = None
    error: Mapping[str, Any] | None = None
    protocol: str = PROTOCOL_VERSION


@dataclass(frozen=True, slots=True)
class Event:
    request_id: str
    type: str
    data: Mapping[str, Any] = field(default_factory=dict)
    native: Mapping[str, Any] | None = None
    protocol: str = PROTOCOL_VERSION


def encode_message(message: Request | Response | Event | Mapping[str, Any]) -> bytes:
    value = asdict(message) if isinstance(message, (Request, Response, Event)) else dict(message)
    if isinstance(message, Event) and message.native is None:
        value.pop("native", None)
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n"


def decode_message(payload: bytes) -> dict[str, Any]:
    value = json.loads(payload.decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError("SubAuth protocol messages must be JSON objects")
    return value
