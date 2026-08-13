from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Any


@dataclass(slots=True)
class ActiveRequest:
    task: asyncio.Task[Any]
    cancellation_requested: bool = False


class ActiveRequestStore:
    """Track provider tasks so a second connection can cancel them by request ID."""

    def __init__(self) -> None:
        self._requests: dict[str, ActiveRequest] = {}
        self._lock = asyncio.Lock()

    async def register(self, request_id: str, task: asyncio.Task[Any]) -> None:
        async with self._lock:
            self._requests[request_id] = ActiveRequest(task=task)

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
