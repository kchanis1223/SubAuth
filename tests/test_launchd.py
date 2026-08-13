from __future__ import annotations

import os
import plistlib
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from subauth.config import Settings  # noqa: E402
from subauth.launchd import LAUNCH_AGENT_LABEL, LaunchAgentManager  # noqa: E402


class LaunchAgentTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        self.home = root / "home"
        self.project = root / "project"
        (self.project / "src").mkdir(parents=True)
        self.python = root / "python3"
        self.python.touch()
        self.manager = LaunchAgentManager(
            home=self.home,
            uid=501,
            python_executable=self.python,
            project_root=self.project,
        )

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def test_plist_is_lazy_and_uses_development_source_tree(self) -> None:
        payload = plistlib.loads(self.manager.render_plist())

        self.assertEqual(payload["Label"], LAUNCH_AGENT_LABEL)
        self.assertFalse(payload["RunAtLoad"])
        self.assertFalse(payload["KeepAlive"])
        self.assertEqual(
            payload["ProgramArguments"],
            [str(self.python.resolve()), "-m", "subauth", "serve"],
        )
        self.assertEqual(
            payload["EnvironmentVariables"]["PYTHONPATH"],
            str(self.project.resolve() / "src"),
        )
        self.assertIn(
            str(self.home.resolve() / ".local" / "bin"),
            payload["EnvironmentVariables"]["PATH"],
        )
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", payload["EnvironmentVariables"])

    def test_write_plist_creates_private_log_directory(self) -> None:
        self.manager.write_plist()

        self.assertTrue(self.manager.plist_path.exists())
        self.assertEqual(self.manager.plist_path.stat().st_mode & 0o777, 0o644)
        self.assertEqual(self.manager.log_dir.stat().st_mode & 0o777, 0o700)

    def test_read_logs_returns_only_requested_tail(self) -> None:
        self.manager.log_dir.mkdir(parents=True)
        self.manager.stdout_path.write_text("one\ntwo\nthree\n")

        logs = self.manager.read_logs(lines=2)

        self.assertEqual(logs["stdout"], ["two", "three"])
        self.assertEqual(logs["stderr"], [])

    def test_launch_agent_only_manages_its_default_socket(self) -> None:
        custom = Settings(
            runtime_dir=self.project,
            socket_path=self.project / "custom.sock",
        )

        self.assertTrue(self.manager.manages(self.manager.settings))
        self.assertFalse(self.manager.manages(custom))

    async def test_environment_override_disables_real_daemon_autostart(self) -> None:
        with patch.dict(os.environ, {"SUBAUTH_RUNTIME_DIR": self.temp_dir.name}):
            self.assertFalse(await self.manager.ensure_running())


if __name__ == "__main__":
    unittest.main()
