from __future__ import annotations

import asyncio
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any, AsyncIterator, Mapping

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from subauth.config import Settings  # noqa: E402
from subauth.daemon.server import SubAuthDaemon  # noqa: E402
from subauth.providers.base import (  # noqa: E402
    AuthState,
    ProviderAdapter,
    ProviderCapabilities,
    ProviderStatus,
    SupportLevel,
    TransportMode,
)
from subauth.providers.registry import ProviderRegistry  # noqa: E402
from subauth.providers.stub import StubProviderAdapter  # noqa: E402
from subauth.sdk import AsyncSubAuth, SubAuthAPIError  # noqa: E402


class SessionProviderAdapter(ProviderAdapter):
    name = "openai"

    def __init__(self) -> None:
        self.requests: list[Mapping[str, Any]] = []

    async def probe(self) -> ProviderStatus:
        return ProviderStatus(
            provider=self.name,
            auth_state=AuthState.READY,
            transport=TransportMode.OFFICIAL_RUNTIME,
            support_level=SupportLevel.OFFICIAL_RUNTIME,
            capabilities=ProviderCapabilities(text=True, streaming=True, sessions=True),
        )

    async def login(self) -> ProviderStatus:
        return await self.probe()

    async def stream(
        self,
        request: Mapping[str, Any],
    ) -> AsyncIterator[Mapping[str, Any]]:
        self.requests.append(dict(request))
        provider_session_id = str(
            request.get("provider_session_id") or "provider-session-test"
        )
        yield {
            "type": "response.started",
            "data": {
                "provider": self.name,
                "provider_session_id": provider_session_id,
                "model": request.get("model"),
            },
        }
        yield {"type": "output.text.delta", "data": {"delta": "SDK_OK"}}
        yield {"type": "response.completed", "data": {"status": "completed"}}


class SlowProviderAdapter(SessionProviderAdapter):
    def __init__(self) -> None:
        super().__init__()
        self.started = asyncio.Event()
        self.cancelled = asyncio.Event()

    async def stream(
        self,
        request: Mapping[str, Any],
    ) -> AsyncIterator[Mapping[str, Any]]:
        self.requests.append(dict(request))
        try:
            yield {
                "type": "response.started",
                "data": {
                    "provider": self.name,
                    "provider_session_id": "provider-session-slow",
                },
            }
            self.started.set()
            await asyncio.Event().wait()
        finally:
            self.cancelled.set()


class SDKTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        runtime_dir = Path(self.temp_dir.name)
        self.settings = Settings(
            runtime_dir=runtime_dir,
            socket_path=runtime_dir / "subauth.sock",
        )
        self.adapter = SessionProviderAdapter()
        registry = ProviderRegistry(
            [
                self.adapter,
                StubProviderAdapter("claude", "sessions unavailable"),
                StubProviderAdapter("gemini", "sessions unavailable"),
            ]
        )
        self.daemon = SubAuthDaemon(settings=self.settings, registry=registry)
        await self.daemon.start()
        self.client = AsyncSubAuth(settings=self.settings, auto_start=False)

    async def asyncTearDown(self) -> None:
        await self.daemon.close()
        self.temp_dir.cleanup()

    async def test_typed_response_create_collects_stream(self) -> None:
        result = await self.client.responses.create(
            provider="openai",
            input="Reply SDK_OK",
        )

        self.assertEqual(result.status, "completed")
        self.assertEqual(result.text, "SDK_OK")
        self.assertTrue(result.request_id)

    async def test_session_binds_and_reuses_provider_session(self) -> None:
        session = await self.client.sessions.create(
            provider="openai",
            model="test-model",
            system="Be concise",
        )

        first = await self.client.responses.create(
            provider="openai",
            input="First",
            session=session,
        )
        stored = await self.client.sessions.retrieve(session.id)
        second = await self.client.responses.create(
            provider="openai",
            input="Second",
            session=stored,
        )

        self.assertEqual(first.text, "SDK_OK")
        self.assertEqual(second.text, "SDK_OK")
        self.assertEqual(stored.provider_session_id, "provider-session-test")
        self.assertEqual(self.adapter.requests[0]["model"], "test-model")
        self.assertEqual(self.adapter.requests[0]["system"], "Be concise")
        self.assertEqual(
            self.adapter.requests[1]["provider_session_id"],
            "provider-session-test",
        )

    async def test_unsupported_provider_session_is_explicit_error(self) -> None:
        with self.assertRaises(SubAuthAPIError) as raised:
            await self.client.sessions.create(provider="claude")

        self.assertEqual(raised.exception.code, "sessions_not_supported")

    async def test_session_rejects_provider_mismatch(self) -> None:
        session = await self.client.sessions.create(provider="openai")

        with self.assertRaises(SubAuthAPIError) as raised:
            await self.client.responses.create(
                provider="claude",
                input="Wrong provider",
                session=session,
            )

        self.assertEqual(raised.exception.code, "session_provider_mismatch")

    async def test_stream_can_be_cancelled_by_request_id(self) -> None:
        slow = SlowProviderAdapter()
        await self.daemon.close()
        self.daemon = SubAuthDaemon(
            settings=self.settings,
            registry=ProviderRegistry([slow]),
        )
        await self.daemon.start()
        stream = self.client.responses.stream(provider="openai", input="Wait")
        events = []

        async def consume() -> None:
            async for event in stream:
                events.append(event)

        consumer = asyncio.create_task(consume())
        await asyncio.wait_for(slow.started.wait(), timeout=1.0)
        cancellation_requested = await stream.cancel()
        await asyncio.wait_for(consumer, timeout=1.0)

        self.assertTrue(cancellation_requested)
        self.assertTrue(slow.cancelled.is_set())
        self.assertEqual(events[-1].type, "response.cancelled")

    async def test_session_list_and_delete(self) -> None:
        session = await self.client.sessions.create(provider="openai")

        sessions = await self.client.sessions.list()
        deleted = await self.client.sessions.delete(session.id)

        self.assertEqual([item.id for item in sessions], [session.id])
        self.assertTrue(deleted)


if __name__ == "__main__":
    unittest.main()
