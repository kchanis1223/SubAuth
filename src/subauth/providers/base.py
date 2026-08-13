from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import asdict, dataclass
from enum import StrEnum
from typing import Any, AsyncIterator, Mapping


class TransportMode(StrEnum):
    AUTO = "auto"
    OFFICIAL_RUNTIME = "official-runtime"
    DIRECT_SUBSCRIPTION = "direct-subscription"
    API = "api"


class SupportLevel(StrEnum):
    OFFICIAL = "official"
    OFFICIAL_RUNTIME = "official-runtime"
    EXPERIMENTAL = "experimental"
    UNAVAILABLE = "unavailable"


class AuthState(StrEnum):
    READY = "ready"
    SIGNED_OUT = "signed-out"
    EXPIRED = "expired"
    UNAVAILABLE = "unavailable"


@dataclass(frozen=True, slots=True)
class ProviderCapabilities:
    text: bool = False
    streaming: bool = False
    vision: bool = False
    files: bool = False
    web_search: bool = False
    hosted_code_execution: bool = False
    function_tools: bool = False
    sessions: bool = False

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class ProviderStatus:
    provider: str
    auth_state: AuthState
    transport: TransportMode
    support_level: SupportLevel
    capabilities: ProviderCapabilities
    detail: str | None = None

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["auth_state"] = self.auth_state.value
        value["transport"] = self.transport.value
        value["support_level"] = self.support_level.value
        return value


class ProviderAdapter(ABC):
    name: str

    @abstractmethod
    async def probe(self) -> ProviderStatus:
        """Inspect local authentication and runtime capabilities without mutating state."""

    @abstractmethod
    async def login(self) -> ProviderStatus:
        """Start the provider login flow and return its resulting status."""

    @abstractmethod
    async def stream(
        self, request: Mapping[str, Any]
    ) -> AsyncIterator[Mapping[str, Any]]:
        """Yield provider-neutral response events for one request."""
        if False:
            yield {}

