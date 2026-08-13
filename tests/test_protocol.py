from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from subauth.protocol.models import Request, decode_message, encode_message  # noqa: E402


class ProtocolTests(unittest.TestCase):
    def test_request_round_trip_preserves_unicode(self) -> None:
        request = Request(
            id="request-1",
            method="responses.create",
            params={"input": "화면을 분석해줘"},
        )

        decoded = decode_message(encode_message(request))

        self.assertEqual(decoded["id"], "request-1")
        self.assertEqual(decoded["params"]["input"], "화면을 분석해줘")

    def test_non_object_message_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "JSON objects"):
            decode_message(b"[]\n")


if __name__ == "__main__":
    unittest.main()

