from __future__ import annotations

import asyncio
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any, AsyncIterator, Mapping

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from subauth.client import SubAuthClient  # noqa: E402
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
from subauth.sdk import AsyncSubAuth  # noqa: E402


class StatelessProviderAdapter(ProviderAdapter):
    name = "openai"

    def __init__(self) -> None:
        self.requests: list[Mapping[str, Any]] = []

    async def probe(self) -> ProviderStatus:
        return ProviderStatus(
            provider=self.name,
            auth_state=AuthState.READY,
            transport=TransportMode.OFFICIAL_RUNTIME,
            support_level=SupportLevel.OFFICIAL_RUNTIME,
            capabilities=ProviderCapabilities(text=True, streaming=True),
        )

    async def login(self) -> ProviderStatus:
        return await self.probe()

    async def stream(
        self,
        request: Mapping[str, Any],
    ) -> AsyncIterator[Mapping[str, Any]]:
        self.requests.append(dict(request))
        yield {
            "type": "response.started",
            "data": {
                "provider": self.name,
                "model": request.get("model"),
            },
        }
        yield {
            "type": "output.text.delta",
            "data": {"delta": "SDK_OK"},
            "native": {"runtime": "test-runtime", "event": {"kind": "delta"}},
        }
        yield {"type": "response.completed", "data": {"status": "completed"}}


class SlowProviderAdapter(StatelessProviderAdapter):
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
        self.adapter = StatelessProviderAdapter()
        registry = ProviderRegistry(
            [
                self.adapter,
                StubProviderAdapter("claude", "unavailable"),
                StubProviderAdapter("gemini", "unavailable"),
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

    async def test_each_request_is_independent(self) -> None:
        first = await self.client.responses.create(
            provider="openai",
            input="First",
            model="test-model",
            system="Be concise",
        )
        second = await self.client.responses.create(
            provider="openai",
            input="Second",
        )

        self.assertEqual(first.text, "SDK_OK")
        self.assertEqual(second.text, "SDK_OK")
        self.assertNotEqual(first.request_id, second.request_id)
        self.assertEqual(self.adapter.requests[0]["model"], "test-model")
        self.assertEqual(self.adapter.requests[0]["system"], "Be concise")
        self.assertNotIn("session_id", self.adapter.requests[0])
        self.assertNotIn("provider_session_id", self.adapter.requests[1])

    async def test_typed_event_exposes_opt_in_native_payload(self) -> None:
        stream = self.client.responses.stream(
            provider="openai",
            input="Reply SDK_OK",
            response_mode="normalized_with_native",
        )

        events = [event async for event in stream]
        delta = next(event for event in events if event.type == "output.text.delta")

        self.assertEqual(delta.native["runtime"], "test-runtime")
        self.assertEqual(delta.native["event"]["kind"], "delta")

    async def test_continuation_handles_are_rejected(self) -> None:
        raw_client = SubAuthClient(self.settings, auto_start=False)

        response = await raw_client.request(
            "responses.create",
            {
                "provider": "openai",
                "input": "Continue",
                "thread_id": "provider-thread",
            },
        )

        self.assertEqual(
            response["error"]["code"],
            "stateful_context_not_supported",
        )

    async def test_session_protocol_methods_are_not_exposed(self) -> None:
        raw_client = SubAuthClient(self.settings, auto_start=False)

        response = await raw_client.request("sessions.create", {"provider": "openai"})

        self.assertEqual(response["error"]["code"], "method_not_found")

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

if __name__ == "__main__":
    unittest.main()
