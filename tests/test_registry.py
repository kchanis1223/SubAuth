from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from subauth.providers.registry import default_registry  # noqa: E402


class RegistryTests(unittest.TestCase):
    def test_default_registry_contains_initial_providers(self) -> None:
        registry = default_registry()
        self.assertEqual(
            [adapter.name for adapter in registry.all()],
            ["claude", "gemini", "openai"],
        )

if __name__ == "__main__":
    unittest.main()
