from __future__ import annotations

import asyncio
import json
import os
import shutil
import tempfile
from collections.abc import AsyncIterator, Sequence
from typing import Any


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
    ) -> None:
        self._command = tuple(command) if command is not None else None
        self._probe_timeout = probe_timeout
        self._login_timeout = login_timeout
        self._request_timeout = request_timeout

    @property
    def executable(self) -> str | None:
        if self._command is not None:
            return self._command[0] if self._command else None
        return shutil.which("claude")

    @property
    def available(self) -> bool:
        return self.executable is not None

    @property
    def setup_token_configured(self) -> bool:
        """Return whether the official setup-token environment variable is present."""
        return bool(os.environ.get("CLAUDE_CODE_OAUTH_TOKEN"))

    async def version(self) -> str:
        result = await self._run_capture(("--version",), timeout=self._probe_timeout)
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
        environment = self._subscription_environment()
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
                        raise ClaudeRuntimeError(f"Claude Code request failed: {detail}")
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
    ) -> str:
        command = (*self._resolve_command(), *args)
        try:
            process = await asyncio.create_subprocess_exec(
                *command,
                env=self._subscription_environment(),
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
            raise ClaudeRuntimeError(f"Claude Code command failed: {detail}")
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

    def _subscription_environment(self) -> dict[str, str]:
        environment = {
            key: value
            for key, value in os.environ.items()
            if key not in self._REMOVED_ENVIRONMENT_KEYS
        }
        environment["CLAUDE_CODE_SAFE_MODE"] = "1"
        return environment
