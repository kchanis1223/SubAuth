from __future__ import annotations

import asyncio
import json
import os
import shutil
import tempfile
from collections.abc import AsyncIterator, Sequence
from pathlib import Path
from typing import Any


class AntigravityRuntimeError(RuntimeError):
    """Base error raised by the Antigravity CLI runtime adapter."""


class AntigravityRuntimeUnavailable(AntigravityRuntimeError):
    """Raised when the Antigravity CLI is unavailable."""


class AntigravityAuthenticationRequired(AntigravityRuntimeError):
    """Raised when Antigravity needs an interactive Google sign-in."""


class AntigravityRuntimeProtocolError(AntigravityRuntimeError):
    """Raised when Antigravity emits an invalid machine-readable response."""


class AntigravityRuntime:
    """Runs OS-keyring-authenticated Antigravity in a constrained workspace."""

    _REMOVED_ENVIRONMENT_KEYS = {
        "AGY_ADC_AUTH",
        "AGY_BUSINESS_PAYGO_TIER",
        "CLOUDSDK_AUTH_ACCESS_TOKEN",
        "CLOUDSDK_CORE_PROJECT",
        "GEMINI_API_KEY",
        "GOOGLE_API_KEY",
        "GOOGLE_APPLICATION_CREDENTIALS",
        "GOOGLE_CLOUD_LOCATION",
        "GOOGLE_CLOUD_PROJECT",
        "GOOGLE_CLOUD_QUOTA_PROJECT",
        "GOOGLE_GENAI_USE_VERTEXAI",
        "GOOGLE_OAUTH_ACCESS_TOKEN",
        "VERTEXAI_LOCATION",
        "VERTEXAI_PROJECT",
    }

    def __init__(
        self,
        command: Sequence[str] | None = None,
        *,
        settings_path: Path | None = None,
        mcp_config_path: Path | None = None,
        probe_timeout: float = 20.0,
        request_timeout: float = 300.0,
    ) -> None:
        self._command = tuple(command) if command is not None else None
        self._settings_path = settings_path or (
            Path.home() / ".gemini" / "antigravity-cli" / "settings.json"
        )
        self._mcp_config_path = mcp_config_path or (
            Path.home() / ".gemini" / "config" / "mcp_config.json"
        )
        self._probe_timeout = probe_timeout
        self._request_timeout = request_timeout

    @property
    def executable(self) -> str | None:
        if self._command is not None:
            return self._command[0] if self._command else None
        return shutil.which("agy") or shutil.which("antigravity")

    @property
    def available(self) -> bool:
        return self.executable is not None

    async def version(self) -> str:
        return (await self._run_capture(("--version",), self._probe_timeout)).strip()

    async def models(self) -> tuple[str, ...]:
        output = await self._run_capture(("models",), self._probe_timeout)
        models: list[str] = []
        for line in output.splitlines():
            fields = line.strip().split(maxsplit=1)
            if fields and fields[0].startswith("gemini-"):
                models.append(fields[0])
        return tuple(dict.fromkeys(models))

    def credit_fallback_enabled(self) -> bool:
        settings = self._read_json_object(self._settings_path)
        return settings.get("useG1Credits") is True or settings.get(
            "use_ai_credits"
        ) is True

    def global_mcp_configured(self) -> bool:
        config = self._read_json_object(self._mcp_config_path)
        servers = config.get("mcpServers")
        return isinstance(servers, dict) and bool(servers)

    async def stream(
        self,
        *,
        prompt: str,
        model: str,
    ) -> AsyncIterator[dict[str, Any]]:
        args = (
            "-p",
            prompt,
            "--model",
            model,
            "--output-format",
            "stream-json",
            "--print-timeout",
            "5m",
            "--sandbox",
            "--disable-slash-commands",
        )
        command = (*self._resolve_command(), *args)
        with tempfile.TemporaryDirectory(prefix="subauth-gemini-") as workdir:
            try:
                process = await asyncio.create_subprocess_exec(
                    *command,
                    cwd=workdir,
                    env=self._subscription_environment(),
                    stdin=asyncio.subprocess.DEVNULL,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE,
                )
            except OSError as error:
                raise AntigravityRuntimeUnavailable(
                    f"Could not start Antigravity CLI: {error}"
                ) from error

            assert process.stdout is not None
            assert process.stderr is not None
            stderr_task = asyncio.create_task(process.stderr.read())
            try:
                async with asyncio.timeout(self._request_timeout):
                    while payload := await process.stdout.readline():
                        try:
                            message = json.loads(payload)
                        except (UnicodeDecodeError, json.JSONDecodeError) as error:
                            raise AntigravityRuntimeProtocolError(
                                "Antigravity CLI emitted malformed stream JSON"
                            ) from error
                        if isinstance(message, dict):
                            yield message
                    return_code = await process.wait()
                    stderr = (await stderr_task).decode("utf-8", errors="replace")
                    if return_code != 0:
                        self._raise_command_error(return_code, stderr)
            except TimeoutError as error:
                raise AntigravityRuntimeError("Antigravity request timed out") from error
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

    async def _run_capture(self, args: Sequence[str], timeout: float) -> str:
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
            raise AntigravityRuntimeUnavailable(
                f"Could not start Antigravity CLI: {error}"
            ) from error
        try:
            stdout, stderr = await asyncio.wait_for(process.communicate(), timeout=timeout)
        except TimeoutError as error:
            process.kill()
            await process.wait()
            raise AntigravityRuntimeError("Antigravity command timed out") from error
        if process.returncode != 0:
            self._raise_command_error(
                process.returncode,
                stderr.decode("utf-8", errors="replace"),
                stdout.decode("utf-8", errors="replace"),
            )
        return stdout.decode("utf-8", errors="replace").strip()

    def _resolve_command(self) -> tuple[str, ...]:
        if self._command is not None:
            return self._command
        executable = self.executable
        if executable is None:
            raise AntigravityRuntimeUnavailable(
                "Antigravity CLI (`agy`) is not installed or not on PATH"
            )
        return (executable,)

    def _subscription_environment(self) -> dict[str, str]:
        return {
            key: value
            for key, value in os.environ.items()
            if key not in self._REMOVED_ENVIRONMENT_KEYS
        }

    @staticmethod
    def _raise_command_error(
        return_code: int | None,
        stderr: str,
        stdout: str = "",
    ) -> None:
        lowered = f"{stderr}\n{stdout}".lower()
        if "authentication required" in lowered or "authenticate" in lowered:
            raise AntigravityAuthenticationRequired(
                "Antigravity requires an interactive Google sign-in"
            )
        raise AntigravityRuntimeError(
            f"Antigravity command failed with exit status {return_code}"
        )

    @staticmethod
    def _read_json_object(path: Path) -> dict[str, Any]:
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (FileNotFoundError, OSError, UnicodeDecodeError, json.JSONDecodeError):
            return {}
        return value if isinstance(value, dict) else {}
