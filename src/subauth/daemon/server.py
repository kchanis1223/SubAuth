from __future__ import annotations

import asyncio
import contextlib
import logging
import os
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from subauth.config import Settings
from subauth.logging import get_logger, log_event, redact_text
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
from subauth.sessions import ActiveRequestStore, SessionError, SessionRecord, SessionStore


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
        self.sessions = SessionStore()
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
        name = str(request.params.get("provider", ""))
        try:
            adapter = self.registry.get(name)
        except KeyError as error:
            writer.write(encode_message(self._error(request.id, "unknown_provider", str(error))))
            await writer.drain()
            return
        params = dict(request.params)
        session_id_value = params.get("session_id")
        session_id = session_id_value if isinstance(session_id_value, str) else None
        session: SessionRecord | None = None
        try:
            if session_id is not None:
                session = await self.sessions.acquire(
                    session_id,
                    provider=name,
                    request_id=request.id,
                )
                if params.get("model") in (None, "auto") and session.model is not None:
                    params["model"] = session.model
                if not params.get("system") and session.system is not None:
                    params["system"] = session.system
                if session.provider_session_id is not None:
                    params["provider_session_id"] = session.provider_session_id

            stream_task = asyncio.create_task(
                self._stream_provider_events(
                    request=request,
                    adapter=adapter,
                    params=params,
                    writer=writer,
                    session_id=session_id,
                )
            )
            await self.active_requests.register(request.id, stream_task, session_id)
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
                                "subauth_session_id": session_id,
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
                    session_id=session_id,
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
        except SessionError as error:
            writer.write(encode_message(self._error(request.id, error.code, str(error))))
            await writer.drain()
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
            if session_id is not None:
                await self.sessions.release(session_id, request.id)

    async def _stream_provider_events(
        self,
        *,
        request: Request,
        adapter: ProviderAdapter,
        params: Mapping[str, Any],
        writer: asyncio.StreamWriter,
        session_id: str | None,
    ) -> int:
        event_count = 0
        async for provider_event in adapter.stream(params):
            event_type = provider_event.get("type")
            data = provider_event.get("data")
            if not isinstance(event_type, str) or not isinstance(data, Mapping):
                continue
            event_data = dict(data)
            if session_id is not None:
                event_data["subauth_session_id"] = session_id
                provider_session_id = event_data.get("provider_session_id")
                if isinstance(provider_session_id, str):
                    await self.sessions.bind_provider_session(
                        session_id,
                        provider_session_id,
                    )
            writer.write(
                encode_message(
                    Event(
                        request_id=request.id,
                        type=event_type,
                        data=event_data,
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
        if request.method == "sessions.create":
            return await self._create_session(request)
        if request.method == "sessions.get":
            return await self._get_session(request)
        if request.method == "sessions.list":
            sessions = await self.sessions.list()
            return Response(
                id=request.id,
                result={"sessions": [session.to_dict() for session in sessions]},
            )
        if request.method == "sessions.delete":
            return await self._delete_session(request)
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

    async def _create_session(self, request: Request) -> Response:
        provider = request.params.get("provider")
        if not isinstance(provider, str) or not provider:
            return self._error(
                request.id,
                "invalid_provider",
                "sessions.create requires a provider",
            )
        try:
            adapter = self.registry.get(provider)
        except KeyError as error:
            return self._error(request.id, "unknown_provider", str(error))
        status = await adapter.probe()
        if not status.capabilities.sessions:
            return self._error(
                request.id,
                "sessions_not_supported",
                f"{provider} does not support resumable sessions in this transport",
            )
        model_value = request.params.get("model")
        model = (
            model_value
            if isinstance(model_value, str) and model_value and model_value != "auto"
            else None
        )
        system_value = request.params.get("system")
        system = system_value if isinstance(system_value, str) and system_value else None
        session = await self.sessions.create(
            provider=provider,
            model=model,
            system=system,
        )
        return Response(id=request.id, result=session.to_dict())

    async def _get_session(self, request: Request) -> Response:
        session_id = request.params.get("session_id")
        if not isinstance(session_id, str) or not session_id:
            return self._error(
                request.id,
                "invalid_session_id",
                "sessions.get requires a session_id",
            )
        try:
            session = await self.sessions.get(session_id)
        except SessionError as error:
            return self._error(request.id, error.code, str(error))
        return Response(id=request.id, result=session.to_dict())

    async def _delete_session(self, request: Request) -> Response:
        session_id = request.params.get("session_id")
        if not isinstance(session_id, str) or not session_id:
            return self._error(
                request.id,
                "invalid_session_id",
                "sessions.delete requires a session_id",
            )
        try:
            deleted = await self.sessions.delete(session_id)
        except SessionError as error:
            return self._error(request.id, error.code, str(error))
        return Response(
            id=request.id,
            result={"session_id": session_id, "deleted": deleted},
        )

    @staticmethod
    def _error(request_id: str, code: str, message: str) -> Response:
        return Response(
            id=request_id,
            error={"code": code, "message": redact_text(message)},
        )
