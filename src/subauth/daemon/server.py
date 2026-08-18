from __future__ import annotations

import asyncio
import contextlib
import logging
import os
from collections.abc import Mapping
from pathlib import Path

from subauth.config import Settings
from subauth.logging import get_logger, log_event, redact_text
from subauth.native import sanitize_native_payload
from subauth.operations import ActiveRequestStore
from subauth.protocol.models import (
    PROTOCOL_VERSION,
    Event,
    Request,
    Response,
    decode_message,
    encode_message,
)
from subauth.providers.base import ProviderAdapter
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
        self._logger = get_logger()
        self.active_requests = ActiveRequestStore()

    async def start(self) -> None:
        self.settings.runtime_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(self.settings.runtime_dir, 0o700)
        await self._remove_stale_socket()
        self._server = await asyncio.start_unix_server(
            self._handle_connection,
            path=str(self.settings.socket_path),
        )
        os.chmod(self.settings.socket_path, 0o600)
        log_event(
            self._logger,
            logging.INFO,
            "daemon.started",
            socket=str(self.settings.socket_path),
            protocol=PROTOCOL_VERSION,
        )

    async def serve_forever(self) -> None:
        if self._server is None:
            await self.start()
        assert self._server is not None
        async with self._server:
            await self._server.serve_forever()

    async def close(self) -> None:
        was_running = self._server is not None
        await self.active_requests.cancel_all()
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
        if was_running:
            log_event(self._logger, logging.INFO, "daemon.stopped")

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
                provider = request.params.get("provider")
                log_event(
                    self._logger,
                    logging.INFO,
                    "request.received",
                    request_id=request.id,
                    method=request.method,
                    provider=provider if isinstance(provider, str) else None,
                )
                if request.method == "responses.create":
                    await self._stream_response(request, writer)
                else:
                    response = await self.dispatch(request)
                    writer.write(encode_message(response))
                    await writer.drain()
                    log_event(
                        self._logger,
                        logging.INFO,
                        "request.completed",
                        request_id=request.id,
                        method=request.method,
                        error_code=(response.error or {}).get("code"),
                    )
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
        stateful_fields = (
            "session_id",
            "provider_session_id",
            "thread_id",
            "conversation_id",
        )
        supplied_fields = [
            field for field in stateful_fields if request.params.get(field) is not None
        ]
        if supplied_fields:
            writer.write(
                encode_message(
                    self._error(
                        request.id,
                        "stateful_context_not_supported",
                        "SubAuth requests are stateless; remove continuation fields: "
                        + ", ".join(supplied_fields),
                    )
                )
            )
            await writer.drain()
            return
        response_mode = request.params.get("response_mode", "normalized")
        if response_mode not in {"normalized", "normalized_with_native"}:
            writer.write(
                encode_message(
                    self._error(
                        request.id,
                        "invalid_response_mode",
                        "response_mode must be normalized or normalized_with_native",
                    )
                )
            )
            await writer.drain()
            return
        name = str(request.params.get("provider", ""))
        try:
            adapter = self.registry.get(name)
        except KeyError as error:
            writer.write(encode_message(self._error(request.id, "unknown_provider", str(error))))
            await writer.drain()
            return
        try:
            stream_task = asyncio.create_task(
                self._stream_provider_events(
                    request=request,
                    adapter=adapter,
                    writer=writer,
                )
            )
            await self.active_requests.register(request.id, stream_task)
            try:
                event_count = await stream_task
            except asyncio.CancelledError:
                if not await self.active_requests.cancellation_requested(request.id):
                    raise
                writer.write(
                    encode_message(
                        Event(
                            request_id=request.id,
                            type="response.cancelled",
                            data={
                                "provider": name,
                                "status": "cancelled",
                            },
                        )
                    )
                )
                await writer.drain()
                event_count = 1
                log_event(
                    self._logger,
                    logging.INFO,
                    "request.cancelled",
                    request_id=request.id,
                    provider=name,
                )
            writer.write(
                encode_message(
                    Response(
                        id=request.id,
                        result={
                            "status": (
                                "cancelled"
                                if await self.active_requests.cancellation_requested(request.id)
                                else "finished"
                            ),
                            "events": event_count,
                        },
                    )
                )
            )
            await writer.drain()
            log_event(
                self._logger,
                logging.INFO,
                "request.completed",
                request_id=request.id,
                method=request.method,
                provider=name,
                events=event_count,
            )
        except Exception as error:
            safe_error = redact_text(str(error))
            log_event(
                self._logger,
                logging.ERROR,
                "request.failed",
                request_id=request.id,
                method=request.method,
                provider=name,
                error=safe_error,
            )
            writer.write(
                encode_message(self._error(request.id, "provider_error", safe_error))
            )
            await writer.drain()
        finally:
            await self.active_requests.unregister(request.id)

    async def _stream_provider_events(
        self,
        *,
        request: Request,
        adapter: ProviderAdapter,
        writer: asyncio.StreamWriter,
    ) -> int:
        event_count = 0
        include_native = request.params.get("response_mode") == "normalized_with_native"
        async for provider_event in adapter.stream(request.params):
            event_type = provider_event.get("type")
            data = provider_event.get("data")
            if not isinstance(event_type, str) or not isinstance(data, Mapping):
                continue
            native_value = provider_event.get("native")
            native = (
                sanitize_native_payload(native_value)
                if include_native and isinstance(native_value, Mapping)
                else None
            )
            writer.write(
                encode_message(
                    Event(
                        request_id=request.id,
                        type=event_type,
                        data=data,
                        native=native,
                    )
                )
            )
            await writer.drain()
            event_count += 1
        return event_count

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
        if request.method == "responses.cancel":
            target = request.params.get("request_id")
            if not isinstance(target, str) or not target:
                return self._error(
                    request.id,
                    "invalid_request_id",
                    "responses.cancel requires a non-empty request_id",
                )
            cancelled = await self.active_requests.cancel(target)
            return Response(
                id=request.id,
                result={
                    "request_id": target,
                    "cancellation_requested": cancelled,
                },
            )
        return self._error(
            request.id,
            "method_not_found",
            f"Unknown method: {request.method}",
        )

    @staticmethod
    def _error(request_id: str, code: str, message: str) -> Response:
        return Response(
            id=request_id,
            error={"code": code, "message": redact_text(message)},
        )
