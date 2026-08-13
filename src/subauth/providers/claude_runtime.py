from __future__ import annotations

import asyncio
import json
import os
import shutil
import tempfile
from collections.abc import AsyncIterator, Sequence
from typing import Any

from subauth.credentials import (
    CLAUDE_SETUP_TOKEN,
    CredentialVault,
    CredentialVaultError,
    default_credential_vault,
)
from subauth.logging import redact_text


class ClaudeRuntimeError(RuntimeError):
    """Base error raised by the Claude Code runtime adapter."""


class ClaudeRuntimeUnavailable(ClaudeRuntimeError):
    """Raised when Claude Code is unavailable."""


class ClaudeRuntimeProtocolError(ClaudeRuntimeError):
    """Raised when Claude Code emits an invalid machine-readable response."""


class ClaudeCodeRuntime:
    """Runs Claude Code with subscription-only credentials and isolated settings."""

    _REMOVED_ENVIRONMENT_KEYS = {
        "ANTHROPIC_API_KEY",
        "ANTHROPIC_AUTH_TOKEN",
        "ANTHROPIC_BASE_URL",
        "ANTHROPIC_CUSTOM_HEADERS",
        "ANTHROPIC_PROFILE",
        "ANTHROPIC_FEDERATION_RULE_ID",
        "ANTHROPIC_IDENTITY_TOKEN_FILE",
        "ANTHROPIC_ORGANIZATION_ID",
        "CLAUDE_CODE_USE_BEDROCK",
        "CLAUDE_CODE_USE_FOUNDRY",
        "CLAUDE_CODE_USE_VERTEX",
        "GOOGLE_APPLICATION_CREDENTIALS",
    }

    def __init__(
        self,
        command: Sequence[str] | None = None,
        *,
        probe_timeout: float = 10.0,
        login_timeout: float = 300.0,
        request_timeout: float = 300.0,
        credential_vault: CredentialVault | None = None,
    ) -> None:
        self._command = tuple(command) if command is not None else None
        self._probe_timeout = probe_timeout
        self._login_timeout = login_timeout
        self._request_timeout = request_timeout
        self._credential_vault = credential_vault or default_credential_vault()

    @property
    def executable(self) -> str | None:
        if self._command is not None:
            return self._command[0] if self._command else None
        return shutil.which("claude")

    @property
    def available(self) -> bool:
        return self.executable is not None

    async def setup_token_storage(self) -> str | None:
        """Return the setup-token location without reading or returning its value."""
        if os.environ.get(CLAUDE_SETUP_TOKEN.environment_variable):
            return "process-environment"
        try:
            if await self._credential_vault.contains(CLAUDE_SETUP_TOKEN):
                return "subauth-keychain"
        except CredentialVaultError as error:
            raise ClaudeRuntimeError("Could not inspect the SubAuth credential vault") from error
        return None

    async def version(self) -> str:
        result = await self._run_capture(
            ("--version",),
            timeout=self._probe_timeout,
            include_credentials=False,
        )
        return result.strip()

    async def auth_status(self) -> dict[str, Any]:
        result = await self._run_capture(
            ("auth", "status", "--json"),
            timeout=self._probe_timeout,
            allow_failure=True,
        )
        try:
            value = json.loads(result)
        except json.JSONDecodeError as error:
            raise ClaudeRuntimeProtocolError(
                "Claude Code auth status did not return valid JSON"
            ) from error
        if not isinstance(value, dict):
            raise ClaudeRuntimeProtocolError("Claude Code auth status must be a JSON object")
        return value

    async def login(self) -> None:
        await self._run_capture(
            ("auth", "login", "--claudeai"),
            timeout=self._login_timeout,
            include_credentials=False,
        )

    async def stream(
        self,
        *,
        prompt: str,
        model: str | None = None,
        system: str | None = None,
    ) -> AsyncIterator[dict[str, Any]]:
        args = [
            "-p",
            prompt,
            "--output-format",
            "stream-json",
            "--verbose",
            "--include-partial-messages",
            "--no-session-persistence",
            "--safe-mode",
            "--disable-slash-commands",
            "--strict-mcp-config",
            "--mcp-config",
            '{"mcpServers":{}}',
            "--permission-mode",
            "dontAsk",
            "--tools",
            "",
        ]
        if model:
            args.extend(("--model", model))
        if system:
            args.extend(("--system-prompt", system))

        command = (*self._resolve_command(), *args)
        environment = await self._subscription_environment()
        setup_token = environment.get(CLAUDE_SETUP_TOKEN.environment_variable)
        with tempfile.TemporaryDirectory(prefix="subauth-claude-") as workdir:
            try:
                process = await asyncio.create_subprocess_exec(
                    *command,
                    cwd=workdir,
                    env=environment,
                    stdin=asyncio.subprocess.DEVNULL,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE,
                )
            except OSError as error:
                raise ClaudeRuntimeUnavailable(f"Could not start Claude Code: {error}") from error

            assert process.stdout is not None
            assert process.stderr is not None
            stderr_task = asyncio.create_task(process.stderr.read())
            try:
                async with asyncio.timeout(self._request_timeout):
                    while payload := await process.stdout.readline():
                        try:
                            message = json.loads(payload)
                        except (UnicodeDecodeError, json.JSONDecodeError) as error:
                            raise ClaudeRuntimeProtocolError(
                                "Claude Code emitted malformed stream JSON"
                            ) from error
                        if isinstance(message, dict):
                            yield message
                    return_code = await process.wait()
                    stderr = (await stderr_task).decode("utf-8", errors="replace").strip()
                    if return_code != 0:
                        detail = stderr or f"exit status {return_code}"
                        raise ClaudeRuntimeError(
                            "Claude Code request failed: "
                            f"{redact_text(detail, (setup_token,))}"
                        )
            except TimeoutError as error:
                raise ClaudeRuntimeError("Claude Code request timed out") from error
            finally:
                if process.returncode is None:
                    process.terminate()
                    try:
                        await asyncio.wait_for(process.wait(), timeout=2.0)
                    except TimeoutError:
                        process.kill()
                        await process.wait()
                if not stderr_task.done():
                    stderr_task.cancel()
                try:
                    await stderr_task
                except asyncio.CancelledError:
                    pass

    async def _run_capture(
        self,
        args: Sequence[str],
        *,
        timeout: float,
        allow_failure: bool = False,
        include_credentials: bool = True,
    ) -> str:
        command = (*self._resolve_command(), *args)
        environment = await self._subscription_environment(
            include_credentials=include_credentials
        )
        setup_token = environment.get(CLAUDE_SETUP_TOKEN.environment_variable)
        try:
            process = await asyncio.create_subprocess_exec(
                *command,
                env=environment,
                stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
        except OSError as error:
            raise ClaudeRuntimeUnavailable(f"Could not start Claude Code: {error}") from error
        try:
            stdout, stderr = await asyncio.wait_for(process.communicate(), timeout=timeout)
        except TimeoutError as error:
            process.kill()
            await process.wait()
            raise ClaudeRuntimeError("Claude Code command timed out") from error
        output = stdout.decode("utf-8", errors="replace").strip()
        if process.returncode != 0 and not allow_failure:
            detail = stderr.decode("utf-8", errors="replace").strip() or output
            raise ClaudeRuntimeError(
                f"Claude Code command failed: {redact_text(detail, (setup_token,))}"
            )
        if not output:
            output = stderr.decode("utf-8", errors="replace").strip()
        return output

    def _resolve_command(self) -> tuple[str, ...]:
        if self._command is not None:
            return self._command
        executable = self.executable
        if executable is None:
            raise ClaudeRuntimeUnavailable("Claude Code is not installed or not on PATH")
        return (executable,)

    async def _subscription_environment(
        self,
        *,
        include_credentials: bool = True,
    ) -> dict[str, str]:
        environment = {
            key: value
            for key, value in os.environ.items()
            if key not in self._REMOVED_ENVIRONMENT_KEYS
        }
        if include_credentials and not environment.get(
            CLAUDE_SETUP_TOKEN.environment_variable
        ):
            try:
                setup_token = await self._credential_vault.get(CLAUDE_SETUP_TOKEN)
            except CredentialVaultError as error:
                raise ClaudeRuntimeError(
                    "Could not read the Claude setup-token from macOS Keychain"
                ) from error
            if setup_token:
                environment[CLAUDE_SETUP_TOKEN.environment_variable] = setup_token
        elif not include_credentials:
            environment.pop(CLAUDE_SETUP_TOKEN.environment_variable, None)
        environment["CLAUDE_CODE_SAFE_MODE"] = "1"
        return environment
