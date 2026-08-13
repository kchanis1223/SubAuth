from __future__ import annotations

import asyncio
import uuid
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from typing import Any


class SessionError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True, slots=True)
class SessionRecord:
    id: str
    provider: str
    model: str | None
    system: str | None
    provider_session_id: str | None
    active_request_id: str | None
    created_at: str
    updated_at: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "provider": self.provider,
            "model": self.model,
            "provider_session_id": self.provider_session_id,
            "active_request_id": self.active_request_id,
            "has_system_instruction": self.system is not None,
            "created_at": self.created_at,
            "updated_at": self.updated_at,
        }


class SessionStore:
    """In-memory provider-neutral sessions owned by one daemon lifetime."""

    def __init__(self) -> None:
        self._sessions: dict[str, SessionRecord] = {}
        self._lock = asyncio.Lock()

    async def create(
        self,
        *,
        provider: str,
        model: str | None = None,
        system: str | None = None,
    ) -> SessionRecord:
        now = _timestamp()
        record = SessionRecord(
            id=str(uuid.uuid4()),
            provider=provider,
            model=model,
            system=system,
            provider_session_id=None,
            active_request_id=None,
            created_at=now,
            updated_at=now,
        )
        async with self._lock:
            self._sessions[record.id] = record
        return record

    async def get(self, session_id: str) -> SessionRecord:
        async with self._lock:
            record = self._sessions.get(session_id)
        if record is None:
            raise SessionError("session_not_found", f"Unknown session: {session_id}")
        return record

    async def list(self) -> tuple[SessionRecord, ...]:
        async with self._lock:
            return tuple(self._sessions.values())

    async def delete(self, session_id: str) -> bool:
        async with self._lock:
            record = self._sessions.get(session_id)
            if record is None:
                return False
            if record.active_request_id is not None:
                raise SessionError(
                    "session_busy",
                    f"Session has an active request: {record.active_request_id}",
                )
            del self._sessions[session_id]
        return True

    async def acquire(
        self,
        session_id: str,
        *,
        provider: str,
        request_id: str,
    ) -> SessionRecord:
        async with self._lock:
            record = self._sessions.get(session_id)
            if record is None:
                raise SessionError("session_not_found", f"Unknown session: {session_id}")
            if record.provider != provider:
                raise SessionError(
                    "session_provider_mismatch",
                    f"Session belongs to {record.provider}, not {provider}",
                )
            if record.active_request_id is not None:
                raise SessionError(
                    "session_busy",
                    f"Session already has an active request: {record.active_request_id}",
                )
            updated = replace(
                record,
                active_request_id=request_id,
                updated_at=_timestamp(),
            )
            self._sessions[session_id] = updated
            return updated

    async def bind_provider_session(
        self,
        session_id: str,
        provider_session_id: str,
    ) -> None:
        async with self._lock:
            record = self._sessions.get(session_id)
            if record is None:
                return
            self._sessions[session_id] = replace(
                record,
                provider_session_id=provider_session_id,
                updated_at=_timestamp(),
            )

    async def release(self, session_id: str, request_id: str) -> None:
        async with self._lock:
            record = self._sessions.get(session_id)
            if record is None or record.active_request_id != request_id:
                return
            self._sessions[session_id] = replace(
                record,
                active_request_id=None,
                updated_at=_timestamp(),
            )


@dataclass(slots=True)
class ActiveRequest:
    task: asyncio.Task[Any]
    session_id: str | None
    cancellation_requested: bool = False


class ActiveRequestStore:
    def __init__(self) -> None:
        self._requests: dict[str, ActiveRequest] = {}
        self._lock = asyncio.Lock()

    async def register(
        self,
        request_id: str,
        task: asyncio.Task[Any],
        session_id: str | None,
    ) -> None:
        async with self._lock:
            self._requests[request_id] = ActiveRequest(task=task, session_id=session_id)

    async def cancel(self, request_id: str) -> bool:
        async with self._lock:
            active = self._requests.get(request_id)
            if active is None or active.task.done():
                return False
            active.cancellation_requested = True
            active.task.cancel()
            return True

    async def cancellation_requested(self, request_id: str) -> bool:
        async with self._lock:
            active = self._requests.get(request_id)
            return bool(active and active.cancellation_requested)

    async def unregister(self, request_id: str) -> None:
        async with self._lock:
            self._requests.pop(request_id, None)

    async def cancel_all(self) -> None:
        async with self._lock:
            requests = tuple(self._requests.values())
            for active in requests:
                active.cancellation_requested = True
                active.task.cancel()
        if requests:
            await asyncio.gather(
                *(active.task for active in requests),
                return_exceptions=True,
            )


def _timestamp() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")
