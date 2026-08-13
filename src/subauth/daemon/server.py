from __future__ import annotations

import asyncio
import contextlib
import os
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from subauth.config import Settings
from subauth.protocol.models import (
    PROTOCOL_VERSION,
    Event,
    Request,
    Response,
    decode_message,
    encode_message,
)
from subauth.providers.registry import ProviderRegistry, default_registry


class SubAuthDaemon:
    def __init__(
        self,
        settings: Settings | None = None,
        registry: ProviderRegistry | None = None,
    ) -> None:
        self.settings = settings or Settings.load()
        self.registry = registry or default_registry()
        self._server: asyncio.AbstractServer | None = None

    async def start(self) -> None:
        self.settings.runtime_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(self.settings.runtime_dir, 0o700)
        await self._remove_stale_socket()
        self._server = await asyncio.start_unix_server(
            self._handle_connection,
            path=str(self.settings.socket_path),
        )
        os.chmod(self.settings.socket_path, 0o600)

    async def serve_forever(self) -> None:
        if self._server is None:
            await self.start()
        assert self._server is not None
        async with self._server:
            await self._server.serve_forever()

    async def close(self) -> None:
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
            self._server = None
        await asyncio.gather(
            *(adapter.close() for adapter in self.registry.all()),
            return_exceptions=True,
        )
        with contextlib.suppress(FileNotFoundError):
            self.settings.socket_path.unlink()

    async def _remove_stale_socket(self) -> None:
        if not self.settings.socket_path.exists():
            return
        try:
            reader, writer = await asyncio.wait_for(
                asyncio.open_unix_connection(str(self.settings.socket_path)),
                timeout=0.2,
            )
        except (ConnectionError, OSError, TimeoutError):
            self.settings.socket_path.unlink(missing_ok=True)
            return
        writer.close()
        await writer.wait_closed()
        del reader
        raise RuntimeError(f"SubAuth daemon is already running at {self.settings.socket_path}")

    async def _handle_connection(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        try:
            while payload := await reader.readline():
                request, error = self._parse_payload(payload)
                if error is not None:
                    writer.write(encode_message(error))
                    await writer.drain()
                    continue
                assert request is not None
                if request.method == "responses.create":
                    await self._stream_response(request, writer)
                else:
                    response = await self.dispatch(request)
                    writer.write(encode_message(response))
                    await writer.drain()
        finally:
            writer.close()
            await writer.wait_closed()

    def _parse_payload(self, payload: bytes) -> tuple[Request | None, Response | None]:
        request_id = "unknown"
        try:
            request = Request.from_dict(decode_message(payload))
            request_id = request.id
            if request.protocol != PROTOCOL_VERSION:
                return None, self._error(
                    request.id,
                    "unsupported_protocol",
                    f"Unsupported protocol version: {request.protocol}",
                )
            return request, None
        except (KeyError, TypeError, ValueError) as error:
            return None, self._error(request_id, "invalid_request", str(error))

    async def _stream_response(
        self,
        request: Request,
        writer: asyncio.StreamWriter,
    ) -> None:
        name = str(request.params.get("provider", ""))
        try:
            adapter = self.registry.get(name)
        except KeyError as error:
            writer.write(encode_message(self._error(request.id, "unknown_provider", str(error))))
            await writer.drain()
            return
        event_count = 0
        try:
            async for provider_event in adapter.stream(request.params):
                event_type = provider_event.get("type")
                data = provider_event.get("data")
                if not isinstance(event_type, str) or not isinstance(data, Mapping):
                    continue
                writer.write(
                    encode_message(Event(request_id=request.id, type=event_type, data=data))
                )
                await writer.drain()
                event_count += 1
            writer.write(
                encode_message(
                    Response(id=request.id, result={"status": "finished", "events": event_count})
                )
            )
            await writer.drain()
        except Exception as error:
            writer.write(encode_message(self._error(request.id, "provider_error", str(error))))
            await writer.drain()

    async def dispatch(self, request: Request) -> Response:
        if request.method == "system.ping":
            return Response(
                id=request.id,
                result={"status": "ok", "protocol": PROTOCOL_VERSION},
            )
        if request.method == "providers.list":
            statuses = [await adapter.probe() for adapter in self.registry.all()]
            return Response(
                id=request.id,
                result={"providers": [status.to_dict() for status in statuses]},
            )
        if request.method == "providers.probe":
            name = str(request.params.get("provider", ""))
            try:
                adapter = self.registry.get(name)
            except KeyError as error:
                return self._error(request.id, "unknown_provider", str(error))
            status = await adapter.probe()
            return Response(id=request.id, result=status.to_dict())
        if request.method == "providers.login":
            name = str(request.params.get("provider", ""))
            try:
                adapter = self.registry.get(name)
            except KeyError as error:
                return self._error(request.id, "unknown_provider", str(error))
            status = await adapter.login()
            return Response(id=request.id, result=status.to_dict())
        return self._error(
            request.id,
            "method_not_found",
            f"Unknown method: {request.method}",
        )

    @staticmethod
    def _error(request_id: str, code: str, message: str) -> Response:
        return Response(id=request_id, error={"code": code, "message": message})
