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
from subauth.providers.gemini_runtime import (
    AntigravityAuthenticationRequired,
    AntigravityRuntime,
    AntigravityRuntimeError,
)

GEMINI_POLICY_WARNING = (
    "Google Antigravity terms restrict using third-party software to access the service. "
    "This official-CLI wrapper is for developer-controlled evaluation only; do not route "
    "external users or production traffic through it without Google authorization."
)


class GeminiAdapter(ProviderAdapter):
    name = "gemini"

    def __init__(self, runtime: AntigravityRuntime | None = None) -> None:
        self._runtime = runtime or AntigravityRuntime()

    async def probe(self) -> ProviderStatus:
        if not self._runtime.available:
            return self._unavailable(
                "Antigravity CLI (`agy`) is not installed or not on PATH",
                install_required=True,
            )
        if self._runtime.credit_fallback_enabled():
            return self._unavailable(
                "Antigravity AI-credit fallback is enabled. Set `useG1Credits` to false "
                "before using the subscription transport."
            )
        try:
            version = await self._runtime.version()
            models = await self._runtime.models()
        except AntigravityAuthenticationRequired:
            return self._signed_out()
        except AntigravityRuntimeError as error:
            return self._unavailable(str(error))

        if not models:
            return self._unavailable(
                "Antigravity did not report any Gemini subscription models."
            )
        return ProviderStatus(
            provider=self.name,
            auth_state=AuthState.READY,
            transport=TransportMode.OFFICIAL_RUNTIME,
            support_level=SupportLevel.EXPERIMENTAL,
            capabilities=ProviderCapabilities(text=True, streaming=True),
            detail=(
                "Gemini models are ready through OS-keyring-authenticated Antigravity CLI. "
                "The runtime does not expose the account plan tier."
            ),
            metadata={
                "runtime": {
                    "name": "antigravity-cli",
                    "executable": self._runtime.executable,
                    "version": version,
                },
                "account": {
                    "credential_source": "os-native-keyring",
                    "subscription_type": "runtime-does-not-expose-plan",
                    "subscription_verification": "runtime-eligibility-only",
                },
                "models": list(models),
                "default_model": models[0],
                "billing": {"ai_credit_fallback": False},
                "isolation": {
                    "temporary_workspace": True,
                    "terminal_sandbox": True,
                    "slash_commands_disabled": True,
                    "runtime_tools_fully_disabled": False,
                    "global_mcp_configured": self._runtime.global_mcp_configured(),
                },
                "policy": self._policy_metadata(),
            },
        )

    async def login(self) -> ProviderStatus:
        status = await self.probe()
        if status.auth_state is AuthState.READY:
            return status
        metadata = dict(status.metadata)
        metadata["login"] = {
            "command": "agy",
            "requires_interactive_terminal": True,
            "detail": (
                "Run `agy` once in a terminal and complete the Google browser sign-in, "
                "then run `subauth probe gemini`."
            ),
        }
        return ProviderStatus(
            provider=status.provider,
            auth_state=status.auth_state,
            transport=status.transport,
            support_level=status.support_level,
            capabilities=status.capabilities,
            detail=status.detail,
            metadata=metadata,
        )

    async def stream(
        self, request: Mapping[str, Any]
    ) -> AsyncIterator[Mapping[str, Any]]:
        input_text = request.get("input")
        if not isinstance(input_text, str) or not input_text.strip():
            yield self._failure("invalid_input", "Gemini input must be a non-empty string.")
            return
        status = await self.probe()
        if status.auth_state is not AuthState.READY:
            yield self._failure("subscription_not_ready", status.detail or "Gemini is not ready.")
            return

        available_models = status.metadata.get("models", [])
        model_value = request.get("model")
        if not isinstance(model_value, str) or model_value == "auto":
            model = status.metadata.get("default_model")
        else:
            model = model_value
        if not isinstance(model, str) or model not in available_models or not model.startswith(
            "gemini-"
        ):
            yield self._failure(
                "invalid_model",
                "The requested model is not an available Gemini model in Antigravity.",
            )
            return

        system_value = request.get("system")
        include_native = request.get("response_mode") == "normalized_with_native"
        prompt = self._compose_prompt(
            input_text,
            system_value if isinstance(system_value, str) else None,
        )
        started = False
        emitted_delta = False
        conversation_id: str | None = None
        try:
            async for message in self._runtime.stream(prompt=prompt, model=model):
                event_type = message.get("event")
                if event_type == "init":
                    conversation_id = self._string_or_none(message.get("conversation_id"))
                    init = message.get("init")
                    if isinstance(init, dict):
                        conversation_id = (
                            self._string_or_none(init.get("conversation_id")) or conversation_id
                        )
                    if not started:
                        yield self._started(conversation_id, model, init, message)
                        started = True
                elif event_type == "step_update":
                    step = message.get("step_update")
                    if not isinstance(step, dict):
                        continue
                    conversation_id = (
                        self._string_or_none(step.get("conversation_id")) or conversation_id
                    )
                    if step.get("step_type") == "tool":
                        yield self._failure(
                            "runtime_tool_use_blocked",
                            "Antigravity attempted a tool call; SubAuth stopped the request.",
                            conversation_id=conversation_id,
                            native_event=message,
                        )
                        return
                    if step.get("step_type") != "agent_response":
                        if include_native:
                            yield self._provider_event(
                                message,
                                str(step.get("step_type") or event_type),
                            )
                        continue
                    text = step.get("text_delta")
                    if isinstance(text, str) and text:
                        if not started:
                            yield self._started(conversation_id, model, None, message)
                            started = True
                        emitted_delta = True
                        yield {
                            "type": "output.text.delta",
                            "data": {"delta": text, "conversation_id": conversation_id},
                            "native": self._native(message),
                        }
                elif event_type == "result":
                    result = message.get("result")
                    if not isinstance(result, dict):
                        yield self._failure(
                            "invalid_runtime_result",
                            "Antigravity returned an invalid result event.",
                            conversation_id=conversation_id,
                            native_event=message,
                        )
                        return
                    conversation_id = (
                        self._string_or_none(result.get("conversation_id")) or conversation_id
                    )
                    if not started:
                        yield self._started(conversation_id, model, None, message)
                        started = True
                    response = result.get("response")
                    if not emitted_delta and isinstance(response, str) and response:
                        yield {
                            "type": "output.text.delta",
                            "data": {
                                "delta": response,
                                "conversation_id": conversation_id,
                            },
                            "native": self._native(message),
                        }
                    if result.get("status") != "SUCCESS":
                        yield self._failure(
                            "antigravity_result_error",
                            str(result.get("error") or result.get("status") or "unknown error"),
                            conversation_id=conversation_id,
                            native_event=message,
                        )
                    else:
                        yield {
                            "type": "response.completed",
                            "data": {
                                "provider": self.name,
                                "transport": TransportMode.OFFICIAL_RUNTIME.value,
                                "conversation_id": conversation_id,
                                "status": "completed",
                                "usage": result.get("usage"),
                                "policy_warning": GEMINI_POLICY_WARNING,
                            },
                            "native": self._native(message),
                        }
                    return
                elif include_native:
                    yield self._provider_event(message, str(event_type or "unknown"))
            yield self._failure(
                "incomplete_runtime_stream",
                "Antigravity ended without a terminal result event.",
                conversation_id=conversation_id,
            )
        except AntigravityRuntimeError as error:
            yield self._failure("antigravity_runtime_error", str(error))

    def _started(
        self,
        conversation_id: str | None,
        model: str,
        init: Any,
        native_event: Mapping[str, Any],
    ) -> dict[str, Any]:
        tools: list[Any] = []
        if isinstance(init, dict) and isinstance(init.get("tools"), list):
            tools = init["tools"]
        return {
            "type": "response.started",
            "data": {
                "provider": self.name,
                "transport": TransportMode.OFFICIAL_RUNTIME.value,
                "support_level": SupportLevel.EXPERIMENTAL.value,
                "conversation_id": conversation_id,
                "model": model,
                "runtime_tools_exposed": bool(tools),
                "policy_warning": GEMINI_POLICY_WARNING,
            },
            "native": self._native(native_event),
        }

    def _failure(
        self,
        code: str,
        message: str,
        *,
        conversation_id: str | None = None,
        native_event: Mapping[str, Any] | None = None,
    ) -> dict[str, Any]:
        failure: dict[str, Any] = {
            "type": "response.failed",
            "data": {
                "code": code,
                "message": message,
                "provider": self.name,
                "conversation_id": conversation_id,
                "policy_warning": GEMINI_POLICY_WARNING,
            },
        }
        if native_event is not None:
            failure["native"] = self._native(native_event)
        return failure

    @staticmethod
    def _native(event: Mapping[str, Any]) -> dict[str, Any]:
        return {"runtime": "antigravity-cli", "event": event}

    def _provider_event(
        self,
        event: Mapping[str, Any],
        native_type: str,
    ) -> dict[str, Any]:
        return {
            "type": "provider.event",
            "data": {"provider": self.name, "native_type": native_type},
            "native": self._native(event),
        }

    def _signed_out(self) -> ProviderStatus:
        return ProviderStatus(
            provider=self.name,
            auth_state=AuthState.SIGNED_OUT,
            transport=TransportMode.OFFICIAL_RUNTIME,
            support_level=SupportLevel.EXPERIMENTAL,
            capabilities=ProviderCapabilities(text=True, streaming=True),
            detail="Antigravity interactive Google sign-in is required.",
            metadata={"policy": self._policy_metadata()},
        )

    def _unavailable(
        self,
        detail: str,
        *,
        install_required: bool = False,
    ) -> ProviderStatus:
        metadata: dict[str, Any] = {
            "runtime": {
                "name": "antigravity-cli",
                "executable": self._runtime.executable,
            },
            "policy": self._policy_metadata(),
        }
        if install_required:
            metadata["install"] = {
                "documentation": "https://antigravity.google/docs/cli/install"
            }
        return ProviderStatus(
            provider=self.name,
            auth_state=AuthState.UNAVAILABLE,
            transport=TransportMode.OFFICIAL_RUNTIME,
            support_level=SupportLevel.UNAVAILABLE,
            capabilities=ProviderCapabilities(),
            detail=detail,
            metadata=metadata,
        )

    @staticmethod
    def _compose_prompt(input_text: str, system: str | None) -> str:
        instructions = (
            "Respond with text only. Do not call tools, subagents, MCP servers, browse, "
            "run commands, read files, or write files."
        )
        if system:
            instructions = f"{instructions}\nAdditional system instruction:\n{system}"
        return f"{instructions}\n\nUser request:\n{input_text}"

    @staticmethod
    def _string_or_none(value: Any) -> str | None:
        return value if isinstance(value, str) and value else None

    @staticmethod
    def _policy_metadata() -> dict[str, str]:
        return {
            "status": "terms-restricted",
            "scope": "developer-controlled-evaluation-only",
            "warning": GEMINI_POLICY_WARNING,
            "documentation": "https://antigravity.google/terms",
        }


def create_adapter() -> GeminiAdapter:
    return GeminiAdapter()
