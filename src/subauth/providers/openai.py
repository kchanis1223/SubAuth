from __future__ import annotations

import asyncio
from typing import Any, AsyncIterator, Mapping

from subauth.providers.base import (
    AuthState,
    ProviderAdapter,
    ProviderCapabilities,
    ProviderStatus,
    SupportLevel,
    TransportMode,
)
from subauth.providers.codex_app_server import CodexAppServer, CodexAppServerError


class OpenAIAdapter(ProviderAdapter):
    name = "openai"

    def __init__(self, app_server: CodexAppServer | None = None) -> None:
        self._app_server = app_server or CodexAppServer()

    async def probe(self) -> ProviderStatus:
        executable = self._app_server.executable
        if not self._app_server.available and not self._app_server.running:
            return self._unavailable("Codex CLI is not installed or not on PATH")
        try:
            account, models = await asyncio.gather(
                self._app_server.request("account/read", {"refreshToken": False}),
                self._app_server.request(
                    "model/list", {"includeHidden": False, "limit": 100}
                ),
            )
        except CodexAppServerError as error:
            return self._unavailable(str(error))

        account_info = account.get("account")
        requires_auth = bool(account.get("requiresOpenaiAuth", True))
        model_data = models.get("data")
        model_list = model_data if isinstance(model_data, list) else []
        model_summary = [
            {
                "id": model.get("id"),
                "model": model.get("model"),
                "display_name": model.get("displayName"),
                "default": bool(model.get("isDefault", False)),
                "input_modalities": list(model.get("inputModalities") or []),
            }
            for model in model_list
            if isinstance(model, dict)
        ]
        supports_vision = any(
            "image" in model.get("input_modalities", []) for model in model_summary
        )

        if not isinstance(account_info, dict):
            auth_state = AuthState.SIGNED_OUT if requires_auth else AuthState.READY
            transport = TransportMode.OFFICIAL_RUNTIME
            support_level = SupportLevel.OFFICIAL_RUNTIME
            safe_account: dict[str, Any] | None = None
            detail = "Codex App Server is available; ChatGPT login is required."
        else:
            account_type = str(account_info.get("type", "unknown"))
            auth_state = AuthState.READY
            transport = (
                TransportMode.OFFICIAL_RUNTIME
                if account_type == "chatgpt"
                else TransportMode.API
            )
            support_level = (
                SupportLevel.OFFICIAL_RUNTIME
                if account_type == "chatgpt"
                else SupportLevel.OFFICIAL
            )
            safe_account = {
                "type": account_type,
                "plan_type": account_info.get("planType"),
            }
            detail = (
                "ChatGPT subscription is ready through Codex App Server."
                if account_type == "chatgpt"
                else f"Codex is authenticated using {account_type}, not a ChatGPT subscription."
            )

        return ProviderStatus(
            provider=self.name,
            auth_state=auth_state,
            transport=transport,
            support_level=support_level,
            capabilities=ProviderCapabilities(
                text=bool(model_summary),
                streaming=True,
                vision=supports_vision,
                sessions=True,
            ),
            detail=detail,
            metadata={
                "runtime": {
                    "name": "codex-app-server",
                    "executable": executable,
                },
                "account": safe_account,
                "models": model_summary,
            },
        )

    async def login(self) -> ProviderStatus:
        status = await self.probe()
        if (
            status.auth_state is AuthState.READY
            and status.transport is TransportMode.OFFICIAL_RUNTIME
        ):
            return status
        if status.support_level is SupportLevel.UNAVAILABLE:
            return status
        try:
            login = await self._app_server.request(
                "account/login/start",
                {"type": "chatgpt", "appBrand": "codex"},
            )
        except CodexAppServerError as error:
            return self._unavailable(str(error))
        metadata = dict(status.metadata)
        metadata["login"] = {
            "type": login.get("type"),
            "login_id": login.get("loginId"),
            "auth_url": login.get("authUrl"),
            "verification_url": login.get("verificationUrl"),
            "user_code": login.get("userCode"),
        }
        return ProviderStatus(
            provider=self.name,
            auth_state=AuthState.SIGNED_OUT,
            transport=TransportMode.OFFICIAL_RUNTIME,
            support_level=SupportLevel.OFFICIAL_RUNTIME,
            capabilities=status.capabilities,
            detail="Open the returned URL to complete ChatGPT login, then run probe again.",
            metadata=metadata,
        )

    async def stream(
        self, request: Mapping[str, Any]
    ) -> AsyncIterator[Mapping[str, Any]]:
        input_text = request.get("input")
        if not isinstance(input_text, str) or not input_text.strip():
            yield {
                "type": "response.failed",
                "data": {
                    "code": "invalid_input",
                    "message": "OpenAI input must be a non-empty string.",
                    "provider": self.name,
                },
            }
            return

        status = await self.probe()
        if (
            status.auth_state is not AuthState.READY
            or status.transport is not TransportMode.OFFICIAL_RUNTIME
        ):
            yield {
                "type": "response.failed",
                "data": {
                    "code": "subscription_not_ready",
                    "message": (
                        "A managed ChatGPT subscription login is required. "
                        "Run `subauth login openai`."
                    ),
                    "provider": self.name,
                },
            }
            return

        model = request.get("model")
        thread_params: dict[str, Any] = {
            "approvalPolicy": "never",
            "ephemeral": True,
            "sandbox": "read-only",
        }
        if isinstance(model, str) and model and model != "auto":
            thread_params["model"] = model
        system = request.get("system")
        if isinstance(system, str) and system:
            thread_params["baseInstructions"] = system

        try:
            thread_result = await self._app_server.request("thread/start", thread_params)
            thread = thread_result.get("thread")
            if not isinstance(thread, dict) or not isinstance(thread.get("id"), str):
                raise ValueError("Codex App Server did not return a thread id")
            thread_id = thread["id"]
            selected_model = thread_result.get("model")
            yield {
                "type": "response.started",
                "data": {
                    "provider": self.name,
                    "transport": TransportMode.OFFICIAL_RUNTIME.value,
                    "thread_id": thread_id,
                    "model": selected_model,
                },
            }

            notifications = self._app_server.subscribe()
            try:
                turn_result = await self._app_server.request(
                    "turn/start",
                    {
                        "threadId": thread_id,
                        "input": [{"type": "text", "text": input_text}],
                    },
                )
                turn = turn_result.get("turn")
                if not isinstance(turn, dict) or not isinstance(turn.get("id"), str):
                    raise ValueError("Codex App Server did not return a turn id")
                turn_id = turn["id"]
                while True:
                    notification = await asyncio.wait_for(notifications.get(), timeout=300.0)
                    method = notification.get("method")
                    params = notification.get("params")
                    if not isinstance(params, dict):
                        continue
                    if params.get("threadId") != thread_id:
                        continue
                    notification_turn_id = params.get("turnId")
                    if notification_turn_id is not None and notification_turn_id != turn_id:
                        continue
                    if method == "item/agentMessage/delta":
                        delta = params.get("delta")
                        if isinstance(delta, str) and delta:
                            yield {
                                "type": "output.text.delta",
                                "data": {
                                    "delta": delta,
                                    "thread_id": thread_id,
                                    "turn_id": turn_id,
                                },
                            }
                    elif method == "error" and not params.get("willRetry", False):
                        error = params.get("error")
                        yield {
                            "type": "response.failed",
                            "data": {
                                "provider": self.name,
                                "thread_id": thread_id,
                                "turn_id": turn_id,
                                "error": error,
                            },
                        }
                        return
                    elif method == "turn/completed":
                        completed_turn = params.get("turn")
                        completed_turn = completed_turn if isinstance(completed_turn, dict) else {}
                        if completed_turn.get("id") != turn_id:
                            continue
                        completed_status = completed_turn.get("status")
                        event_type = (
                            "response.completed"
                            if completed_status == "completed"
                            else "response.failed"
                        )
                        yield {
                            "type": event_type,
                            "data": {
                                "provider": self.name,
                                "transport": TransportMode.OFFICIAL_RUNTIME.value,
                                "thread_id": thread_id,
                                "turn_id": turn_id,
                                "status": completed_status,
                                "error": completed_turn.get("error"),
                            },
                        }
                        return
            finally:
                self._app_server.unsubscribe(notifications)
        except (CodexAppServerError, TimeoutError, ValueError) as error:
            yield {
                "type": "response.failed",
                "data": {
                    "code": "openai_runtime_error",
                    "message": str(error),
                    "provider": self.name,
                },
            }

    async def close(self) -> None:
        await self._app_server.close()

    def _unavailable(self, detail: str) -> ProviderStatus:
        return ProviderStatus(
            provider=self.name,
            auth_state=AuthState.UNAVAILABLE,
            transport=TransportMode.OFFICIAL_RUNTIME,
            support_level=SupportLevel.UNAVAILABLE,
            capabilities=ProviderCapabilities(),
            detail=detail,
            metadata={"runtime": {"name": "codex-app-server"}},
        )


def create_adapter() -> OpenAIAdapter:
    return OpenAIAdapter()
