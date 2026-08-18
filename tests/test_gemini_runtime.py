from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from subauth.providers.base import AuthState, SupportLevel, TransportMode  # noqa: E402
from subauth.providers.gemini import GeminiAdapter  # noqa: E402
from subauth.providers.gemini_runtime import AntigravityRuntime  # noqa: E402

FIXTURE = Path(__file__).parent / "fixtures" / "fake_antigravity.py"


class GeminiRuntimeTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory(prefix="subauth-gemini-test-")
        self.root = Path(self.tempdir.name)

    def tearDown(self) -> None:
        self.tempdir.cleanup()

    def runtime(self) -> AntigravityRuntime:
        return AntigravityRuntime(
            (sys.executable, str(FIXTURE)),
            settings_path=self.root / "settings.json",
            mcp_config_path=self.root / "mcp_config.json",
        )

    async def test_probe_reports_gemini_models_and_policy_boundary(self) -> None:
        status = await GeminiAdapter(self.runtime()).probe()

        self.assertEqual(status.auth_state, AuthState.READY)
        self.assertEqual(status.transport, TransportMode.OFFICIAL_RUNTIME)
        self.assertEqual(status.support_level, SupportLevel.EXPERIMENTAL)
        self.assertEqual(status.metadata["default_model"], "gemini-test-high")
        self.assertEqual(status.metadata["policy"]["status"], "terms-restricted")
        self.assertFalse(status.metadata["billing"]["ai_credit_fallback"])

    async def test_runtime_removes_api_key_before_model_probe(self) -> None:
        with patch.dict(os.environ, {"GEMINI_API_KEY": "secret-api-key"}):
            status = await GeminiAdapter(self.runtime()).probe()

        self.assertEqual(status.auth_state, AuthState.READY)
        self.assertNotIn("secret-api-key", repr(status.to_dict()))

    async def test_credit_fallback_blocks_subscription_transport(self) -> None:
        (self.root / "settings.json").write_text(
            json.dumps({"useG1Credits": True}),
            encoding="utf-8",
        )

        status = await GeminiAdapter(self.runtime()).probe()

        self.assertEqual(status.auth_state, AuthState.UNAVAILABLE)
        self.assertIn("credit fallback", status.detail.lower())

    async def test_login_returns_interactive_keyring_instructions(self) -> None:
        with patch.dict(os.environ, {"FAKE_AGY_SIGNED_OUT": "1"}):
            status = await GeminiAdapter(self.runtime()).login()

        self.assertEqual(status.auth_state, AuthState.SIGNED_OUT)
        self.assertEqual(status.metadata["login"]["command"], "agy")
        self.assertTrue(status.metadata["login"]["requires_interactive_terminal"])

    async def test_stream_normalizes_antigravity_events(self) -> None:
        events = [
            event
            async for event in GeminiAdapter(self.runtime()).stream(
                {"input": "Reply GEMINI_OK"}
            )
        ]

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
            "GEMINI_OK",
        )
        self.assertTrue(events[0]["data"]["runtime_tools_exposed"])
        delta = next(event for event in events if event["type"] == "output.text.delta")
        self.assertEqual(delta["native"]["runtime"], "antigravity-cli")
        self.assertEqual(delta["native"]["event"]["event"], "step_update")

    async def test_stream_stops_when_runtime_attempts_tool_use(self) -> None:
        with patch.dict(os.environ, {"FAKE_AGY_TOOL": "1"}):
            events = [
                event
                async for event in GeminiAdapter(self.runtime()).stream(
                    {"input": "Do not use tools"}
                )
            ]

        self.assertEqual(events[-1]["type"], "response.failed")
        self.assertEqual(events[-1]["data"]["code"], "runtime_tool_use_blocked")

    async def test_native_mode_forwards_unmapped_step_updates(self) -> None:
        events = [
            event
            async for event in GeminiAdapter(self.runtime()).stream(
                {
                    "input": "Reply GEMINI_OK",
                    "response_mode": "normalized_with_native",
                }
            )
        ]

        native_event = next(event for event in events if event["type"] == "provider.event")
        self.assertEqual(native_event["data"]["native_type"], "user_input")
        self.assertEqual(native_event["native"]["runtime"], "antigravity-cli")


if __name__ == "__main__":
    unittest.main()
