from __future__ import annotations

import io
import sys
import unittest
from contextlib import redirect_stderr
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from subauth.cli import _warn_provider_policy  # noqa: E402


class CliTests(unittest.TestCase):
    def test_claude_policy_warning_is_printed_before_use(self) -> None:
        output = io.StringIO()

        with redirect_stderr(output):
            _warn_provider_policy("claude")

        self.assertIn("WARNING:", output.getvalue())
        self.assertIn("development", output.getvalue())

    def test_openai_does_not_print_claude_policy_warning(self) -> None:
        output = io.StringIO()

        with redirect_stderr(output):
            _warn_provider_policy("openai")

        self.assertEqual(output.getvalue(), "")

    def test_gemini_policy_warning_is_printed_before_use(self) -> None:
        output = io.StringIO()

        with redirect_stderr(output):
            _warn_provider_policy("gemini")

        self.assertIn("WARNING:", output.getvalue())
        self.assertIn("Google authorization", output.getvalue())


if __name__ == "__main__":
    unittest.main()
