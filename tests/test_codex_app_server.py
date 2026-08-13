from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from subauth.providers.base import AuthState, TransportMode  # noqa: E402
from subauth.providers.codex_app_server import CodexAppServer  # noqa: E402
from subauth.providers.openai import OpenAIAdapter  # noqa: E402

FIXTURE = Path(__file__).parent / "fixtures" / "fake_codex_app_server.py"


class CodexAppServerTests(unittest.IsolatedAsyncioTestCase):
    async def test_request_initializes_and_reads_account(self) -> None:
        worker = CodexAppServer((sys.executable, str(FIXTURE)))
        self.addAsyncCleanup(worker.close)

        result = await worker.request("account/read", {"refreshToken": False})

        self.assertEqual(result["account"]["type"], "chatgpt")

    async def test_openai_probe_reports_subscription_and_capabilities(self) -> None:
        worker = CodexAppServer((sys.executable, str(FIXTURE)))
        adapter = OpenAIAdapter(worker)
        self.addAsyncCleanup(adapter.close)

        status = await adapter.probe()

        self.assertEqual(status.auth_state, AuthState.READY)
        self.assertEqual(status.transport, TransportMode.OFFICIAL_RUNTIME)
        self.assertTrue(status.capabilities.text)
        self.assertTrue(status.capabilities.streaming)
        self.assertTrue(status.capabilities.vision)
        self.assertEqual(status.metadata["account"]["plan_type"], "plus")
        self.assertNotIn("email", status.metadata["account"])

    async def test_openai_login_returns_managed_browser_flow(self) -> None:
        worker = CodexAppServer((sys.executable, str(FIXTURE), "--signed-out"))
        adapter = OpenAIAdapter(worker)
        self.addAsyncCleanup(adapter.close)

        status = await adapter.login()

        self.assertEqual(status.auth_state, AuthState.SIGNED_OUT)
        self.assertEqual(
            status.metadata["login"]["auth_url"],
            "https://example.test/login",
        )

    async def test_openai_stream_normalizes_codex_notifications(self) -> None:
        worker = CodexAppServer((sys.executable, str(FIXTURE)))
        adapter = OpenAIAdapter(worker)
        self.addAsyncCleanup(adapter.close)

        events = [event async for event in adapter.stream({"input": "Reply OK"})]

        self.assertEqual(
            [event["type"] for event in events],
            [
                "response.started",
                "output.text.delta",
                "output.text.delta",
                "response.completed",
            ],
        )
        self.assertEqual(
            "".join(
                event["data"]["delta"]
                for event in events
                if event["type"] == "output.text.delta"
            ),
            "OK",
        )

    async def test_openai_stream_does_not_silently_use_api_key_auth(self) -> None:
        worker = CodexAppServer((sys.executable, str(FIXTURE), "--api-key"))
        adapter = OpenAIAdapter(worker)
        self.addAsyncCleanup(adapter.close)

        events = [event async for event in adapter.stream({"input": "Reply OK"})]

        self.assertEqual(events[0]["type"], "response.failed")
        self.assertEqual(events[0]["data"]["code"], "subscription_not_ready")

    async def test_openai_reuses_active_provider_session(self) -> None:
        worker = CodexAppServer((sys.executable, str(FIXTURE)))
        adapter = OpenAIAdapter(worker)
        self.addAsyncCleanup(adapter.close)

        events = [
            event
            async for event in adapter.stream(
                {
                    "input": "Continue",
                    "provider_session_id": "thread-existing",
                    "model": "test-model",
                }
            )
        ]

        self.assertEqual(events[0]["data"]["thread_id"], "thread-existing")
        self.assertEqual(events[0]["data"]["provider_session_id"], "thread-existing")
        self.assertEqual(events[0]["data"]["model"], "test-model")


if __name__ == "__main__":
    unittest.main()
