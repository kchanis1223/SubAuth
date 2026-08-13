from __future__ import annotations

from typing import Any, AsyncIterator, Mapping

from subauth.providers.base import (
    AuthState,
    ProviderAdapter,
    ProviderCapabilities,
    ProviderStatus,
    SupportLevel,
    TransportMode,
)


class StubProviderAdapter(ProviderAdapter):
    def __init__(self, name: str, detail: str) -> None:
        self.name = name
        self._detail = detail

    async def probe(self) -> ProviderStatus:
        return ProviderStatus(
            provider=self.name,
            auth_state=AuthState.UNAVAILABLE,
            transport=TransportMode.AUTO,
            support_level=SupportLevel.UNAVAILABLE,
            capabilities=ProviderCapabilities(),
            detail=self._detail,
        )

    async def login(self) -> ProviderStatus:
        return await self.probe()

    async def stream(
        self, request: Mapping[str, Any]
    ) -> AsyncIterator[Mapping[str, Any]]:
        del request
        yield {
            "type": "response.failed",
            "data": {
                "code": "provider_not_implemented",
                "message": self._detail,
                "provider": self.name,
            },
        }

