from __future__ import annotations

from collections.abc import Iterable

from subauth.providers.base import ProviderAdapter
from subauth.providers.claude import create_adapter as create_claude_adapter
from subauth.providers.gemini import create_adapter as create_gemini_adapter
from subauth.providers.openai import create_adapter as create_openai_adapter


class ProviderRegistry:
    def __init__(self, adapters: Iterable[ProviderAdapter] = ()) -> None:
        self._adapters: dict[str, ProviderAdapter] = {}
        for adapter in adapters:
            self.register(adapter)

    def register(self, adapter: ProviderAdapter) -> None:
        if adapter.name in self._adapters:
            raise ValueError(f"Provider already registered: {adapter.name}")
        self._adapters[adapter.name] = adapter

    def get(self, name: str) -> ProviderAdapter:
        try:
            return self._adapters[name]
        except KeyError as error:
            raise KeyError(f"Unknown provider: {name}") from error

    def all(self) -> tuple[ProviderAdapter, ...]:
        return tuple(self._adapters[name] for name in sorted(self._adapters))


def default_registry() -> ProviderRegistry:
    return ProviderRegistry(
        [create_openai_adapter(), create_claude_adapter(), create_gemini_adapter()]
    )

