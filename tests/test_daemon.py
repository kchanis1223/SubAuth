from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from subauth.client import SubAuthClient  # noqa: E402
from subauth.config import Settings  # noqa: E402
from subauth.daemon.server import SubAuthDaemon  # noqa: E402
from subauth.providers.registry import ProviderRegistry  # noqa: E402
from subauth.providers.stub import StubProviderAdapter  # noqa: E402


class StreamingStubProviderAdapter(StubProviderAdapter):
    async def stream(self, request):
        del request
        yield {"type": "response.started", "data": {"provider": self.name}}
        yield {"type": "output.text.delta", "data": {"delta": "OK"}}
        yield {"type": "response.completed", "data": {"status": "completed"}}


class DaemonTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        runtime_dir = Path(self.temp_dir.name)
        self.settings = Settings(
            runtime_dir=runtime_dir,
            socket_path=runtime_dir / "subauth.sock",
        )
        registry = ProviderRegistry(
            [
                StreamingStubProviderAdapter("openai", "test stub"),
                StubProviderAdapter("claude", "test stub"),
                StubProviderAdapter("gemini", "test stub"),
            ]
        )
        self.daemon = SubAuthDaemon(settings=self.settings, registry=registry)
        await self.daemon.start()

    async def asyncTearDown(self) -> None:
        await self.daemon.close()
        self.temp_dir.cleanup()

    async def test_ping_over_unix_socket(self) -> None:
        response = await SubAuthClient(self.settings).request("system.ping")
        self.assertEqual(response["result"]["status"], "ok")

    async def test_provider_status_over_unix_socket(self) -> None:
        response = await SubAuthClient(self.settings).request("providers.list")
        providers = response["result"]["providers"]
        self.assertEqual(
            [provider["provider"] for provider in providers],
            ["claude", "gemini", "openai"],
        )

    async def test_single_provider_probe_over_unix_socket(self) -> None:
        response = await SubAuthClient(self.settings).request(
            "providers.probe", {"provider": "openai"}
        )
        self.assertEqual(response["result"]["provider"], "openai")

    async def test_normalized_events_stream_over_unix_socket(self) -> None:
        events = [
            event
            async for event in SubAuthClient(self.settings).stream(
                "responses.create",
                {"provider": "openai", "input": "Reply OK"},
            )
        ]
        self.assertEqual(
            [event["type"] for event in events],
            ["response.started", "output.text.delta", "response.completed"],
        )

    async def test_client_starts_daemon_on_first_connection(self) -> None:
        await self.daemon.close()
        starts = 0

        async def starter() -> bool:
            nonlocal starts
            starts += 1
            await self.daemon.start()
            return True

        response = await SubAuthClient(
            self.settings,
            starter=starter,
        ).request("system.ping")

        self.assertEqual(response["result"]["status"], "ok")
        self.assertEqual(starts, 1)

    def test_protocol_errors_redact_credential_assignments(self) -> None:
        response = self.daemon._error(
            "request-test",
            "provider_error",
            "CLAUDE_CODE_OAUTH_TOKEN=must-not-leak",
        )

        self.assertNotIn("must-not-leak", repr(response.error))
        self.assertIn("[REDACTED]", repr(response.error))


if __name__ == "__main__":
    unittest.main()
