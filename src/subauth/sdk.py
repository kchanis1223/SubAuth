from __future__ import annotations

import contextlib
import uuid
from collections.abc import AsyncGenerator, AsyncIterator, Mapping
from dataclasses import dataclass, field
from typing import Any, Literal

from subauth.client import SubAuthClient
from subauth.config import Settings


ProviderName = Literal["openai", "claude", "gemini"]
TERMINAL_EVENT_TYPES = {
    "response.completed",
    "response.failed",
    "response.cancelled",
}


class SubAuthError(RuntimeError):
    pass


class SubAuthAPIError(SubAuthError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True, slots=True)
class ResponseEvent:
    request_id: str
    type: str
    data: Mapping[str, Any] = field(default_factory=dict)

    @classmethod
    def from_message(cls, message: Mapping[str, Any]) -> ResponseEvent:
        request_id = message.get("request_id")
        event_type = message.get("type")
        data = message.get("data")
        if not isinstance(request_id, str) or not isinstance(event_type, str):
            raise SubAuthError("Invalid response event received from the daemon")
        return cls(
            request_id=request_id,
            type=event_type,
            data=dict(data) if isinstance(data, Mapping) else {},
        )


@dataclass(frozen=True, slots=True)
class ResponseResult:
    request_id: str
    status: str
    text: str
    events: tuple[ResponseEvent, ...]


@dataclass(frozen=True, slots=True)
class Session:
    id: str
    provider: str
    model: str | None
    provider_session_id: str | None
    active_request_id: str | None
    has_system_instruction: bool
    created_at: str
    updated_at: str

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> Session:
        return cls(
            id=_required_string(value, "id"),
            provider=_required_string(value, "provider"),
            model=_optional_string(value.get("model")),
            provider_session_id=_optional_string(value.get("provider_session_id")),
            active_request_id=_optional_string(value.get("active_request_id")),
            has_system_instruction=bool(value.get("has_system_instruction", False)),
            created_at=_required_string(value, "created_at"),
            updated_at=_required_string(value, "updated_at"),
        )


class ResponseStream:
    def __init__(
        self,
        client: SubAuthClient,
        params: Mapping[str, Any],
    ) -> None:
        self.request_id = str(uuid.uuid4())
        self._client = client
        self._params = dict(params)
        self._iterator: AsyncGenerator[ResponseEvent, None] | None = None
        self._started = False
        self._terminal = False
        self._cancel_requested = False

    def __aiter__(self) -> AsyncIterator[ResponseEvent]:
        if self._iterator is None:
            self._iterator = self._iterate()
        return self._iterator

    async def __aenter__(self) -> ResponseStream:
        return self

    async def __aexit__(self, *exc_info: object) -> None:
        del exc_info
        await self.aclose()

    async def cancel(self) -> bool:
        if self._terminal or self._cancel_requested or not self._started:
            return False
        self._cancel_requested = True
        response = await self._client.request(
            "responses.cancel",
            {"request_id": self.request_id},
        )
        result = _unwrap(response)
        return bool(result.get("cancellation_requested", False))

    async def aclose(self) -> None:
        if self._started and not self._terminal:
            await self.cancel()
        iterator = self._iterator
        if iterator is not None:
            await iterator.aclose()

    async def _iterate(self) -> AsyncGenerator[ResponseEvent, None]:
        self._started = True
        try:
            async for message in self._client.stream(
                "responses.create",
                self._params,
                request_id=self.request_id,
            ):
                error = message.get("error")
                if isinstance(error, Mapping):
                    self._terminal = True
                    raise _api_error(error)
                event = ResponseEvent.from_message(message)
                if event.type in TERMINAL_EVENT_TYPES:
                    self._terminal = True
                yield event
        finally:
            if not self._terminal and not self._cancel_requested:
                with contextlib.suppress(Exception):
                    await self.cancel()


class AsyncResponses:
    def __init__(self, client: SubAuthClient) -> None:
        self._client = client

    def stream(
        self,
        *,
        provider: ProviderName,
        input: str,
        model: str = "auto",
        system: str | None = None,
        session: Session | str | None = None,
    ) -> ResponseStream:
        params: dict[str, Any] = {
            "provider": provider,
            "input": input,
            "model": model,
        }
        if system is not None:
            params["system"] = system
        if session is not None:
            params["session_id"] = session.id if isinstance(session, Session) else session
        return ResponseStream(self._client, params)

    async def create(
        self,
        *,
        provider: ProviderName,
        input: str,
        model: str = "auto",
        system: str | None = None,
        session: Session | str | None = None,
    ) -> ResponseResult:
        stream = self.stream(
            provider=provider,
            input=input,
            model=model,
            system=system,
            session=session,
        )
        events = [event async for event in stream]
        failed = next((event for event in events if event.type == "response.failed"), None)
        if failed is not None:
            code = str(failed.data.get("code") or "response_failed")
            message = str(
                failed.data.get("message")
                or failed.data.get("error")
                or "Provider response failed"
            )
            raise SubAuthAPIError(code, message)
        text = "".join(
            str(event.data.get("delta", ""))
            for event in events
            if event.type == "output.text.delta"
        )
        status = "cancelled" if any(
            event.type == "response.cancelled" for event in events
        ) else "completed"
        return ResponseResult(
            request_id=stream.request_id,
            status=status,
            text=text,
            events=tuple(events),
        )

    async def cancel(self, request_id: str) -> bool:
        response = await self._client.request(
            "responses.cancel",
            {"request_id": request_id},
        )
        return bool(_unwrap(response).get("cancellation_requested", False))


class AsyncSessions:
    def __init__(self, client: SubAuthClient) -> None:
        self._client = client

    async def create(
        self,
        *,
        provider: ProviderName,
        model: str = "auto",
        system: str | None = None,
    ) -> Session:
        params: dict[str, Any] = {"provider": provider, "model": model}
        if system is not None:
            params["system"] = system
        return Session.from_dict(_unwrap(await self._client.request("sessions.create", params)))

    async def retrieve(self, session_id: str) -> Session:
        response = await self._client.request(
            "sessions.get",
            {"session_id": session_id},
        )
        return Session.from_dict(_unwrap(response))

    async def list(self) -> tuple[Session, ...]:
        result = _unwrap(await self._client.request("sessions.list"))
        values = result.get("sessions")
        if not isinstance(values, list):
            raise SubAuthError("Invalid session list received from the daemon")
        return tuple(
            Session.from_dict(value) for value in values if isinstance(value, Mapping)
        )

    async def delete(self, session_id: str) -> bool:
        response = await self._client.request(
            "sessions.delete",
            {"session_id": session_id},
        )
        return bool(_unwrap(response).get("deleted", False))


class AsyncSubAuth:
    """Typed asynchronous SDK for a local SubAuth daemon."""

    def __init__(
        self,
        *,
        settings: Settings | None = None,
        auto_start: bool = True,
    ) -> None:
        self._client = SubAuthClient(settings, auto_start=auto_start)
        self.responses = AsyncResponses(self._client)
        self.sessions = AsyncSessions(self._client)

    async def ping(self) -> bool:
        response = await self._client.request("system.ping")
        return _unwrap(response).get("status") == "ok"

    async def providers(self) -> tuple[Mapping[str, Any], ...]:
        result = _unwrap(await self._client.request("providers.list"))
        providers = result.get("providers")
        if not isinstance(providers, list):
            raise SubAuthError("Invalid provider list received from the daemon")
        return tuple(value for value in providers if isinstance(value, Mapping))


def _unwrap(response: Mapping[str, Any]) -> Mapping[str, Any]:
    error = response.get("error")
    if isinstance(error, Mapping):
        raise _api_error(error)
    result = response.get("result")
    if not isinstance(result, Mapping):
        raise SubAuthError("Invalid response received from the daemon")
    return result


def _api_error(error: Mapping[str, Any]) -> SubAuthAPIError:
    return SubAuthAPIError(
        str(error.get("code") or "subauth_error"),
        str(error.get("message") or "SubAuth request failed"),
    )


def _required_string(value: Mapping[str, Any], key: str) -> str:
    item = value.get(key)
    if not isinstance(item, str):
        raise SubAuthError(f"Invalid session field: {key}")
    return item


def _optional_string(value: Any) -> str | None:
    return value if isinstance(value, str) else None
