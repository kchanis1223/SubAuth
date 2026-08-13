from __future__ import annotations

import asyncio
import uuid
from typing import Any, Mapping

from subauth.config import Settings
from subauth.protocol.models import Request, decode_message, encode_message


class SubAuthClient:
    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or Settings.load()

    async def request(
        self,
        method: str,
        params: Mapping[str, Any] | None = None,
    ) -> dict[str, Any]:
        reader, writer = await asyncio.open_unix_connection(str(self.settings.socket_path))
        try:
            request = Request(id=str(uuid.uuid4()), method=method, params=params or {})
            writer.write(encode_message(request))
            await writer.drain()
            payload = await reader.readline()
            if not payload:
                raise ConnectionError("SubAuth daemon closed the connection without a response")
            return decode_message(payload)
        finally:
            writer.close()
            await writer.wait_closed()

