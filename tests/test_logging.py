from __future__ import annotations

import io
import json
import logging
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from subauth.logging import (  # noqa: E402
    REDACTED,
    configure_structured_logging,
    log_event,
    redact_text,
)


class LoggingTests(unittest.TestCase):
    def test_redacts_assignments_bearer_tokens_and_known_values(self) -> None:
        secret = "raw-secret-value"
        message = (
            f"token={secret} Authorization: Bearer abc.def "
            f"CLAUDE_CODE_OAUTH_TOKEN={secret}"
        )

        redacted = redact_text(message, (secret,))

        self.assertNotIn(secret, redacted)
        self.assertNotIn("abc.def", redacted)
        self.assertIn(REDACTED, redacted)

    def test_structured_logger_redacts_sensitive_fields(self) -> None:
        output = io.StringIO()
        logger = configure_structured_logging(output)

        log_event(
            logger,
            logging.INFO,
            "credential.checked",
            provider="claude",
            access_token="should-not-appear",
            nested={"api_key": "also-secret"},
        )

        payload = json.loads(output.getvalue())
        self.assertEqual(payload["event"], "credential.checked")
        self.assertEqual(payload["provider"], "claude")
        self.assertEqual(payload["access_token"], REDACTED)
        self.assertEqual(payload["nested"]["api_key"], REDACTED)
        self.assertNotIn("should-not-appear", output.getvalue())
        self.assertNotIn("also-secret", output.getvalue())


if __name__ == "__main__":
    unittest.main()
