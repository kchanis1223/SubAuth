from __future__ import annotations

import asyncio
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from subauth.providers.base import AuthState  # noqa: E402
from subauth.providers.registry import default_registry  # noqa: E402


class RegistryTests(unittest.TestCase):
    def test_default_registry_contains_initial_providers(self) -> None:
        registry = default_registry()
        self.assertEqual(
            [adapter.name for adapter in registry.all()],
            ["claude", "gemini", "openai"],
        )

    def test_stub_provider_reports_unavailable(self) -> None:
        status = asyncio.run(default_registry().get("claude").probe())
        self.assertEqual(status.auth_state, AuthState.UNAVAILABLE)


if __name__ == "__main__":
    unittest.main()

