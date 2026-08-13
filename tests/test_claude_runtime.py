from __future__ import annotations

import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from subauth.credentials import CredentialRef, NullCredentialVault  # noqa: E402
from subauth.providers.base import AuthState, SupportLevel, TransportMode  # noqa: E402
from subauth.providers.claude import ClaudeAdapter  # noqa: E402
from subauth.providers.claude_runtime import ClaudeCodeRuntime  # noqa: E402

FIXTURE = Path(__file__).parent / "fixtures" / "fake_claude.py"


class FakeCredentialVault(NullCredentialVault):
    def __init__(self, value: str | None) -> None:
        self.value = value

    @property
    def available(self) -> bool:
        return True

    async def contains(self, credential: CredentialRef) -> bool:
        del credential
        return self.value is not None

    async def get(self, credential: CredentialRef) -> str | None:
        del credential
        return self.value


class ClaudeRuntimeTests(unittest.IsolatedAsyncioTestCase):
    def runtime(self) -> ClaudeCodeRuntime:
        return ClaudeCodeRuntime(
            (sys.executable, str(FIXTURE)),
            credential_vault=NullCredentialVault(),
        )

    async def test_probe_reports_subscription_without_identity_fields(self) -> None:
        adapter = ClaudeAdapter(self.runtime())

        status = await adapter.probe()

        self.assertEqual(status.auth_state, AuthState.READY)
        self.assertEqual(status.transport, TransportMode.OFFICIAL_RUNTIME)
        self.assertEqual(status.support_level, SupportLevel.EXPERIMENTAL)
        self.assertEqual(status.metadata["account"]["subscription_type"], "max")
        self.assertNotIn("email", status.metadata["account"])
        self.assertNotIn("org_id", status.metadata["account"])

    async def test_runtime_removes_api_key_before_auth_probe(self) -> None:
        adapter = ClaudeAdapter(self.runtime())

        with patch.dict(os.environ, {"ANTHROPIC_API_KEY": "secret-test-key"}):
            status = await adapter.probe()

        self.assertEqual(status.auth_state, AuthState.READY)
        self.assertEqual(status.metadata["account"]["auth_method"], "claude.ai")

    async def test_probe_accepts_setup_token_without_exposing_it(self) -> None:
        adapter = ClaudeAdapter(self.runtime())

        with patch.dict(
            os.environ,
            {"CLAUDE_CODE_OAUTH_TOKEN": "secret-setup-token"},
        ):
            status = await adapter.probe()

        self.assertEqual(status.auth_state, AuthState.READY)
        self.assertEqual(status.metadata["account"]["auth_method"], "oauth_token")
        self.assertEqual(status.metadata["account"]["credential_source"], "setup-token")
        self.assertEqual(
            status.metadata["account"]["credential_storage"],
            "process-environment",
        )
        self.assertNotIn("secret-setup-token", repr(status.to_dict()))

    async def test_probe_injects_setup_token_from_keychain_vault(self) -> None:
        runtime = ClaudeCodeRuntime(
            (sys.executable, str(FIXTURE)),
            credential_vault=FakeCredentialVault("secret-keychain-token"),
        )

        status = await ClaudeAdapter(runtime).probe()

        self.assertEqual(status.auth_state, AuthState.READY)
        self.assertEqual(status.metadata["account"]["auth_method"], "oauth_token")
        self.assertEqual(status.metadata["account"]["credential_source"], "setup-token")
        self.assertEqual(
            status.metadata["account"]["credential_storage"],
            "subauth-keychain",
        )
        self.assertNotIn("secret-keychain-token", repr(status.to_dict()))

    async def test_stream_normalizes_partial_messages(self) -> None:
        adapter = ClaudeAdapter(self.runtime())

        events = [event async for event in adapter.stream({"input": "Reply CLAUDE_OK"})]

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
            "CLAUDE_OK",
        )
        self.assertIn("policy_warning", events[0]["data"])

    async def test_runtime_error_redacts_injected_setup_token(self) -> None:
        secret = "secret-runtime-token"
        with patch.dict(
            os.environ,
            {
                "CLAUDE_CODE_OAUTH_TOKEN": secret,
                "FAKE_CLAUDE_ECHO_TOKEN_ERROR": "1",
            },
        ):
            events = [
                event
                async for event in ClaudeAdapter(self.runtime()).stream(
                    {"input": "Trigger error"}
                )
            ]

        self.assertEqual(events[-1]["type"], "response.failed")
        self.assertNotIn(secret, repr(events))
        self.assertIn("[REDACTED]", repr(events))


if __name__ == "__main__":
    unittest.main()
