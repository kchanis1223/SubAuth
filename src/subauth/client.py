from __future__ import annotations

import asyncio
import uuid
from collections.abc import Awaitable, Callable
from typing import Any, AsyncIterator, Mapping

from subauth.config import Settings
from subauth.protocol.models import Request, decode_message, encode_message


class SubAuthClient:
    def __init__(
        self,
        settings: Settings | None = None,
        *,
        auto_start: bool = True,
        starter: Callable[[], Awaitable[bool]] | None = None,
    ) -> None:
        self.settings = settings or Settings.load()
        self.auto_start = auto_start
        self._starter = starter

    async def _connect(self) -> tuple[asyncio.StreamReader, asyncio.StreamWriter]:
        try:
            return await asyncio.open_unix_connection(str(self.settings.socket_path))
        except (ConnectionError, FileNotFoundError, OSError) as original_error:
            if not self.auto_start:
                raise
            starter = self._starter
            if starter is None:
                from subauth.launchd import LaunchAgentManager

                manager = LaunchAgentManager()
                if not manager.manages(self.settings):
                    raise original_error
                starter = manager.ensure_running
            try:
                started = await starter()
            except Exception as start_error:
                raise ConnectionError(
                    f"SubAuth daemon could not be started: {start_error}"
                ) from start_error
            if not started:
                raise original_error

            deadline = asyncio.get_running_loop().time() + 5.0
            while True:
                try:
                    return await asyncio.open_unix_connection(str(self.settings.socket_path))
                except (ConnectionError, FileNotFoundError, OSError):
                    if asyncio.get_running_loop().time() >= deadline:
                        raise original_error
                    await asyncio.sleep(0.05)

    async def request(
        self,
        method: str,
        params: Mapping[str, Any] | None = None,
    ) -> dict[str, Any]:
        reader, writer = await self._connect()
        try:
            request = Request(id=str(uuid.uuid4()), method=method, params=params or {})
            writer.write(encode_message(request))
            await writer.drain()
            payload = await reader.readline()
            if not payload:
                raise ConnectionError("SubAuth daemon closed the connection without a response")
            return decode_message(payload)
        finally:
            writer.close()
            await writer.wait_closed()

    async def stream(
        self,
        method: str,
        params: Mapping[str, Any] | None = None,
    ) -> AsyncIterator[dict[str, Any]]:
        reader, writer = await self._connect()
        request = Request(id=str(uuid.uuid4()), method=method, params=params or {})
        try:
            writer.write(encode_message(request))
            await writer.drain()
            while payload := await reader.readline():
                message = decode_message(payload)
                if message.get("request_id") == request.id:
                    yield message
                    continue
                if message.get("id") == request.id:
                    if message.get("error") is not None:
                        yield message
                    return
            raise ConnectionError("SubAuth daemon closed the streaming connection unexpectedly")
        finally:
            writer.close()
            await writer.wait_closed()
