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
from subauth.providers.claude_runtime import ClaudeCodeRuntime, ClaudeRuntimeError

CLAUDE_POLICY_WARNING = (
    "Anthropic does not permit third-party products to route user requests through "
    "consumer Claude subscription credentials. Use this transport only for development "
    "and limited previews, and migrate to an Anthropic API key before formal release."
)

_STORED_SUBSCRIPTION_AUTH_METHODS = {"claude.ai", "claudeai"}


class ClaudeAdapter(ProviderAdapter):
    name = "claude"

    def __init__(self, runtime: ClaudeCodeRuntime | None = None) -> None:
        self._runtime = runtime or ClaudeCodeRuntime()

    async def probe(self) -> ProviderStatus:
        if not self._runtime.available:
            return self._unavailable("Claude Code is not installed or not on PATH")
        try:
            version = await self._runtime.version()
            auth = await self._runtime.auth_status()
        except ClaudeRuntimeError as error:
            return self._unavailable(str(error))

        logged_in = auth.get("loggedIn") is True
        auth_method = str(auth.get("authMethod") or "unknown")
        subscription_type = auth.get("subscriptionType")
        stored_subscription_ready = (
            logged_in
            and isinstance(subscription_type, str)
            and bool(subscription_type.strip())
            and auth_method in _STORED_SUBSCRIPTION_AUTH_METHODS
        )
        setup_token_ready = (
            logged_in
            and auth_method == "oauth_token"
            and self._runtime.setup_token_configured
        )
        subscription_ready = stored_subscription_ready or setup_token_ready
        if setup_token_ready:
            credential_source = "setup-token"
        elif stored_subscription_ready:
            credential_source = "stored-login"
        else:
            credential_source = "unsupported-or-none"
        if subscription_ready:
            auth_state = AuthState.READY
            if setup_token_ready:
                detail = (
                    "Claude subscription setup-token is ready through the official "
                    "Claude Code runtime."
                )
            else:
                detail = (
                    "Claude subscription is ready through the official Claude Code runtime."
                )
        elif logged_in:
            auth_state = AuthState.SIGNED_OUT
            detail = (
                "Claude Code is authenticated, but not with a verified Claude subscription. "
                "Run `subauth login claude`."
            )
        else:
            auth_state = AuthState.SIGNED_OUT
            detail = "Claude Code is available; Claude subscription login is required."

        return ProviderStatus(
            provider=self.name,
            auth_state=auth_state,
            transport=TransportMode.OFFICIAL_RUNTIME,
            support_level=SupportLevel.EXPERIMENTAL,
            capabilities=ProviderCapabilities(
                text=True,
                streaming=True,
                sessions=False,
            ),
            detail=detail,
            metadata={
                "runtime": {
                    "name": "claude-code",
                    "executable": self._runtime.executable,
                    "version": version,
                },
                "account": {
                    "auth_method": auth_method,
                    "credential_source": credential_source,
                    "subscription_type": subscription_type,
                },
                "policy": {
                    "status": "provider-discouraged",
                    "scope": "development-and-limited-preview-only",
                    "warning": CLAUDE_POLICY_WARNING,
                    "documentation": "https://code.claude.com/docs/en/legal-and-compliance",
                },
            },
        )

    async def login(self) -> ProviderStatus:
        status = await self.probe()
        if status.auth_state is AuthState.READY:
            return status
        if status.support_level is SupportLevel.UNAVAILABLE:
            return status
        try:
            await self._runtime.login()
        except ClaudeRuntimeError as error:
            return self._unavailable(str(error))
        return await self.probe()

    async def stream(
        self, request: Mapping[str, Any]
    ) -> AsyncIterator[Mapping[str, Any]]:
        input_text = request.get("input")
        if not isinstance(input_text, str) or not input_text.strip():
            yield self._failure("invalid_input", "Claude input must be a non-empty string.")
            return
        status = await self.probe()
        if status.auth_state is not AuthState.READY:
            yield self._failure(
                "subscription_not_ready",
                "A Claude subscription login is required. Run `subauth login claude`.",
            )
            return

        model_value = request.get("model")
        model = model_value if isinstance(model_value, str) and model_value != "auto" else None
        system_value = request.get("system")
        system = system_value if isinstance(system_value, str) and system_value else None
        started = False
        emitted_delta = False
        session_id: str | None = None
        selected_model: str | None = model
        try:
            async for message in self._runtime.stream(
                prompt=input_text,
                model=model,
                system=system,
            ):
                message_type = message.get("type")
                if message_type == "system" and message.get("subtype") == "init":
                    session_id = self._string_or_none(message.get("session_id"))
                    selected_model = self._string_or_none(message.get("model")) or selected_model
                    if not started:
                        yield self._started(session_id, selected_model)
                        started = True
                elif message_type == "stream_event":
                    event = message.get("event")
                    if not isinstance(event, dict):
                        continue
                    if event.get("type") != "content_block_delta":
                        continue
                    delta = event.get("delta")
                    if not isinstance(delta, dict) or delta.get("type") != "text_delta":
                        continue
                    text = delta.get("text")
                    if isinstance(text, str) and text:
                        if not started:
                            yield self._started(session_id, selected_model)
                            started = True
                        emitted_delta = True
                        yield {
                            "type": "output.text.delta",
                            "data": {"delta": text, "session_id": session_id},
                        }
                elif message_type == "result":
                    session_id = self._string_or_none(message.get("session_id")) or session_id
                    if not started:
                        yield self._started(session_id, selected_model)
                        started = True
                    result_text = message.get("result")
                    if not emitted_delta and isinstance(result_text, str) and result_text:
                        yield {
                            "type": "output.text.delta",
                            "data": {"delta": result_text, "session_id": session_id},
                        }
                    if message.get("is_error") is True or message.get("subtype") != "success":
                        yield {
                            "type": "response.failed",
                            "data": {
                                "provider": self.name,
                                "transport": TransportMode.OFFICIAL_RUNTIME.value,
                                "session_id": session_id,
                                "error": result_text or message.get("subtype"),
                                "policy_warning": CLAUDE_POLICY_WARNING,
                            },
                        }
                    else:
                        yield {
                            "type": "response.completed",
                            "data": {
                                "provider": self.name,
                                "transport": TransportMode.OFFICIAL_RUNTIME.value,
                                "session_id": session_id,
                                "status": "completed",
                                "usage": message.get("usage"),
                                "policy_warning": CLAUDE_POLICY_WARNING,
                            },
                        }
                    return
        except ClaudeRuntimeError as error:
            yield self._failure("claude_runtime_error", str(error))

    def _started(self, session_id: str | None, model: str | None) -> dict[str, Any]:
        return {
            "type": "response.started",
            "data": {
                "provider": self.name,
                "transport": TransportMode.OFFICIAL_RUNTIME.value,
                "support_level": SupportLevel.EXPERIMENTAL.value,
                "session_id": session_id,
                "model": model,
                "policy_warning": CLAUDE_POLICY_WARNING,
            },
        }

    def _failure(self, code: str, message: str) -> dict[str, Any]:
        return {
            "type": "response.failed",
            "data": {
                "code": code,
                "message": message,
                "provider": self.name,
                "policy_warning": CLAUDE_POLICY_WARNING,
            },
        }

    def _unavailable(self, detail: str) -> ProviderStatus:
        return ProviderStatus(
            provider=self.name,
            auth_state=AuthState.UNAVAILABLE,
            transport=TransportMode.OFFICIAL_RUNTIME,
            support_level=SupportLevel.UNAVAILABLE,
            capabilities=ProviderCapabilities(),
            detail=detail,
            metadata={
                "runtime": {"name": "claude-code", "executable": self._runtime.executable},
                "policy": {"status": "provider-discouraged", "warning": CLAUDE_POLICY_WARNING},
            },
        )

    @staticmethod
    def _string_or_none(value: Any) -> str | None:
        return value if isinstance(value, str) and value else None


def create_adapter() -> ClaudeAdapter:
    return ClaudeAdapter()
