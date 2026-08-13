from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from subauth.client import SubAuthClient  # noqa: E402
from subauth.config import Settings  # noqa: E402
from subauth.daemon.server import SubAuthDaemon  # noqa: E402


class DaemonTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        runtime_dir = Path(self.temp_dir.name)
        self.settings = Settings(
            runtime_dir=runtime_dir,
            socket_path=runtime_dir / "subauth.sock",
        )
        self.daemon = SubAuthDaemon(settings=self.settings)
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


if __name__ == "__main__":
    unittest.main()

