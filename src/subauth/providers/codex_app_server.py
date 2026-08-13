from __future__ import annotations

import asyncio
import json
import shutil
from collections import deque
from collections.abc import Mapping, Sequence
from typing import Any


class CodexAppServerError(RuntimeError):
    """Base error raised by the Codex App Server transport."""


class CodexAppServerUnavailable(CodexAppServerError):
    """Raised when the Codex runtime cannot be found or started."""


class CodexAppServerProtocolError(CodexAppServerError):
    """Raised for malformed or unsuccessful JSON-RPC responses."""


class CodexAppServer:
    """Small async JSON-RPC client for a persistent `codex app-server` process."""

    def __init__(
        self,
        command: Sequence[str] | None = None,
        *,
        request_timeout: float = 10.0,
    ) -> None:
        self._command = tuple(command) if command is not None else None
        self._request_timeout = request_timeout
        self._process: asyncio.subprocess.Process | None = None
        self._reader_task: asyncio.Task[None] | None = None
        self._stderr_task: asyncio.Task[None] | None = None
        self._pending: dict[int, asyncio.Future[dict[str, Any]]] = {}
        self._subscribers: set[asyncio.Queue[dict[str, Any]]] = set()
        self._next_id = 1
        self._write_lock = asyncio.Lock()
        self._start_lock = asyncio.Lock()
        self._stderr_tail: deque[str] = deque(maxlen=20)

    @property
    def running(self) -> bool:
        return self._process is not None and self._process.returncode is None

    @property
    def executable(self) -> str | None:
        if self._command is not None:
            return self._command[0] if self._command else None
        return shutil.which("codex")

    @property
    def available(self) -> bool:
        return self.executable is not None

    @property
    def stderr_tail(self) -> tuple[str, ...]:
        return tuple(self._stderr_tail)

    def subscribe(self) -> asyncio.Queue[dict[str, Any]]:
        queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
        self._subscribers.add(queue)
        return queue

    def unsubscribe(self, queue: asyncio.Queue[dict[str, Any]]) -> None:
        self._subscribers.discard(queue)

    async def start(self) -> None:
        async with self._start_lock:
            if self.running:
                return
            command = self._resolve_command()
            try:
                self._process = await asyncio.create_subprocess_exec(
                    *command,
                    stdin=asyncio.subprocess.PIPE,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE,
                )
            except OSError as error:
                raise CodexAppServerUnavailable(
                    f"Could not start Codex App Server: {error}"
                ) from error

            self._reader_task = asyncio.create_task(self._read_stdout())
            self._stderr_task = asyncio.create_task(self._read_stderr())
            try:
                await self.request(
                    "initialize",
                    {
                        "clientInfo": {
                            "name": "subauth",
                            "title": "SubAuth",
                            "version": "0.1.0.dev0",
                        },
                        "capabilities": {"experimentalApi": False},
                    },
                    ensure_started=False,
                )
                await self.notify("initialized", {}, ensure_started=False)
            except Exception:
                await self.close()
                raise

    async def request(
        self,
        method: str,
        params: Mapping[str, Any] | None = None,
        *,
        ensure_started: bool = True,
    ) -> dict[str, Any]:
        if ensure_started:
            await self.start()
        process = self._require_process()
        if process.stdin is None:
            raise CodexAppServerProtocolError("Codex App Server stdin is unavailable")

        request_id = self._next_id
        self._next_id += 1
        future = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future
        message = {"method": method, "id": request_id, "params": dict(params or {})}
        try:
            async with self._write_lock:
                process.stdin.write(self._encode(message))
                await process.stdin.drain()
            response = await asyncio.wait_for(future, timeout=self._request_timeout)
        except TimeoutError as error:
            raise CodexAppServerProtocolError(
                f"Codex App Server timed out handling {method}"
            ) from error
        finally:
            self._pending.pop(request_id, None)

        if "error" in response:
            error = response["error"]
            raise CodexAppServerProtocolError(
                f"Codex App Server rejected {method}: {error}"
            )
        result = response.get("result")
        if not isinstance(result, dict):
            raise CodexAppServerProtocolError(
                f"Codex App Server returned an invalid result for {method}"
            )
        return result

    async def notify(
        self,
        method: str,
        params: Mapping[str, Any] | None = None,
        *,
        ensure_started: bool = True,
    ) -> None:
        if ensure_started:
            await self.start()
        process = self._require_process()
        if process.stdin is None:
            raise CodexAppServerProtocolError("Codex App Server stdin is unavailable")
        async with self._write_lock:
            process.stdin.write(self._encode({"method": method, "params": dict(params or {})}))
            await process.stdin.drain()

    async def close(self) -> None:
        process = self._process
        self._process = None
        if process is not None and process.returncode is None:
            process.terminate()
            try:
                await asyncio.wait_for(process.wait(), timeout=2.0)
            except TimeoutError:
                process.kill()
                await process.wait()
        for task in (self._reader_task, self._stderr_task):
            if task is not None and not task.done():
                task.cancel()
        for task in (self._reader_task, self._stderr_task):
            if task is not None:
                try:
                    await task
                except (asyncio.CancelledError, CodexAppServerError):
                    pass
        self._reader_task = None
        self._stderr_task = None
        self._fail_pending(CodexAppServerUnavailable("Codex App Server stopped"))

    def _resolve_command(self) -> tuple[str, ...]:
        if self._command is not None:
            return self._command
        executable = self.executable
        if executable is None:
            raise CodexAppServerUnavailable("Codex CLI is not installed or not on PATH")
        return (executable, "app-server", "--stdio")

    def _require_process(self) -> asyncio.subprocess.Process:
        if not self.running or self._process is None:
            detail = "; ".join(self._stderr_tail)
            suffix = f" ({detail})" if detail else ""
            raise CodexAppServerUnavailable(f"Codex App Server is not running{suffix}")
        return self._process

    async def _read_stdout(self) -> None:
        process = self._require_process()
        assert process.stdout is not None
        try:
            while payload := await process.stdout.readline():
                try:
                    message = json.loads(payload)
                except (UnicodeDecodeError, json.JSONDecodeError) as error:
                    raise CodexAppServerProtocolError(
                        "Codex App Server emitted malformed JSON"
                    ) from error
                if not isinstance(message, dict):
                    continue
                request_id = message.get("id")
                if isinstance(request_id, int):
                    future = self._pending.get(request_id)
                    if future is not None and not future.done():
                        future.set_result(message)
                elif isinstance(message.get("method"), str):
                    for queue in tuple(self._subscribers):
                        queue.put_nowait(message)
            return_code = await process.wait()
            raise CodexAppServerUnavailable(
                f"Codex App Server exited with status {return_code}"
            )
        except asyncio.CancelledError:
            raise
        except CodexAppServerError as error:
            self._fail_pending(error)

    async def _read_stderr(self) -> None:
        process = self._require_process()
        assert process.stderr is not None
        try:
            while payload := await process.stderr.readline():
                line = payload.decode("utf-8", errors="replace").strip()
                if line:
                    self._stderr_tail.append(line)
        except asyncio.CancelledError:
            raise

    def _fail_pending(self, error: CodexAppServerError) -> None:
        for future in self._pending.values():
            if not future.done():
                future.set_exception(error)

    @staticmethod
    def _encode(message: Mapping[str, Any]) -> bytes:
        return json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode() + b"\n"
