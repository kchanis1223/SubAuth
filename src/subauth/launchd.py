from __future__ import annotations

import asyncio
import os
import plistlib
import re
import sys
from collections.abc import Sequence
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from subauth.config import Settings


LAUNCH_AGENT_LABEL = "io.github.kchanis1223.subauth"


class LaunchAgentError(RuntimeError):
    pass


@dataclass(frozen=True, slots=True)
class CommandResult:
    returncode: int
    stdout: str
    stderr: str


@dataclass(frozen=True, slots=True)
class LaunchAgentStatus:
    installed: bool
    loaded: bool
    running: bool
    socket_available: bool
    pid: int | None
    label: str
    plist_path: str
    stdout_path: str
    stderr_path: str

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


class LaunchAgentManager:
    """Install and control the per-user macOS launchd service."""

    def __init__(
        self,
        *,
        home: Path | None = None,
        uid: int | None = None,
        python_executable: Path | None = None,
        project_root: Path | None = None,
        launchctl: Sequence[str] = ("launchctl",),
    ) -> None:
        self.home = (home or Path.home()).expanduser().resolve()
        self.uid = os.getuid() if uid is None else uid
        self.python_executable = (
            python_executable or Path(sys.executable)
        ).expanduser().resolve()
        self.project_root = (
            project_root or Path(__file__).resolve().parents[2]
        ).expanduser().resolve()
        self.launchctl = tuple(launchctl)

        self.plist_path = (
            self.home / "Library" / "LaunchAgents" / f"{LAUNCH_AGENT_LABEL}.plist"
        )
        self.log_dir = self.home / "Library" / "Logs" / "SubAuth"
        self.stdout_path = self.log_dir / "daemon.stdout.log"
        self.stderr_path = self.log_dir / "daemon.stderr.log"

    @property
    def domain(self) -> str:
        return f"gui/{self.uid}"

    @property
    def service_target(self) -> str:
        return f"{self.domain}/{LAUNCH_AGENT_LABEL}"

    @property
    def source_root(self) -> Path:
        return self.project_root / "src"

    @property
    def settings(self) -> Settings:
        return _launch_agent_settings(self.uid)

    def manages(self, settings: Settings) -> bool:
        return settings.socket_path == self.settings.socket_path

    def plist_payload(self) -> dict[str, Any]:
        path_entries = [
            str(self.home / ".local" / "bin"),
            "/opt/homebrew/bin",
            "/usr/local/bin",
            "/usr/bin",
            "/bin",
            "/usr/sbin",
            "/sbin",
        ]
        return {
            "Label": LAUNCH_AGENT_LABEL,
            "Program": str(self.python_executable),
            "ProgramArguments": [
                str(self.python_executable),
                "-m",
                "subauth",
                "serve",
            ],
            "WorkingDirectory": str(self.project_root),
            "EnvironmentVariables": {
                "PATH": ":".join(path_entries),
                "PYTHONPATH": str(self.source_root),
                "PYTHONUNBUFFERED": "1",
            },
            "RunAtLoad": False,
            "KeepAlive": False,
            "ProcessType": "Standard",
            "ThrottleInterval": 2,
            "ExitTimeOut": 10,
            "Umask": 0o077,
            "StandardOutPath": str(self.stdout_path),
            "StandardErrorPath": str(self.stderr_path),
        }

    def render_plist(self) -> bytes:
        return plistlib.dumps(self.plist_payload(), fmt=plistlib.FMT_XML, sort_keys=True)

    def write_plist(self) -> None:
        self.plist_path.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
        self.log_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        temporary = self.plist_path.with_suffix(".plist.tmp")
        temporary.write_bytes(self.render_plist())
        temporary.chmod(0o644)
        temporary.replace(self.plist_path)

    async def install(self) -> LaunchAgentStatus:
        self.write_plist()
        await self._run("bootout", self.service_target, check=False)
        await self._run("bootstrap", self.domain, str(self.plist_path))
        await self._run("enable", self.service_target)
        await self._run("kickstart", self.service_target)
        return self._require_running(await self._wait_for_status(running=True))

    async def uninstall(self) -> LaunchAgentStatus:
        await self._run("bootout", self.service_target, check=False)
        self.plist_path.unlink(missing_ok=True)
        return await self.status()

    async def start(self) -> LaunchAgentStatus:
        if not self.plist_path.exists():
            raise LaunchAgentError(
                f"LaunchAgent is not installed; run `subauth daemon install` first"
            )
        if not await self.is_loaded():
            await self._run("bootstrap", self.domain, str(self.plist_path))
            await self._run("enable", self.service_target)
        await self._run("kickstart", self.service_target)
        return self._require_running(await self._wait_for_status(running=True))

    async def stop(self) -> LaunchAgentStatus:
        if await self.is_loaded():
            await self._run("kill", "SIGTERM", self.service_target, check=False)
        return await self._wait_for_status(running=False)

    async def restart(self) -> LaunchAgentStatus:
        if not self.plist_path.exists():
            raise LaunchAgentError(
                f"LaunchAgent is not installed; run `subauth daemon install` first"
            )
        if not await self.is_loaded():
            return await self.start()
        await self._run("kickstart", "-k", self.service_target)
        return self._require_running(await self._wait_for_status(running=True))

    async def is_loaded(self) -> bool:
        result = await self._run("print", self.service_target, check=False)
        return result.returncode == 0

    async def status(self) -> LaunchAgentStatus:
        result = await self._run("print", self.service_target, check=False)
        loaded = result.returncode == 0
        match = re.search(r"\bpid\s*=\s*(\d+)", result.stdout) if loaded else None
        pid = int(match.group(1)) if match else None
        socket_available = await socket_is_available(self.settings)
        return LaunchAgentStatus(
            installed=self.plist_path.exists(),
            loaded=loaded,
            running=pid is not None,
            socket_available=socket_available,
            pid=pid,
            label=LAUNCH_AGENT_LABEL,
            plist_path=str(self.plist_path),
            stdout_path=str(self.stdout_path),
            stderr_path=str(self.stderr_path),
        )

    async def ensure_running(self) -> bool:
        if os.environ.get("SUBAUTH_RUNTIME_DIR") or os.environ.get("SUBAUTH_SOCKET"):
            return False
        if await socket_is_available(self.settings):
            return True
        if not self.plist_path.exists():
            return False
        await self.start()
        return True

    def read_logs(self, lines: int = 100) -> dict[str, list[str]]:
        if lines < 1:
            raise ValueError("lines must be at least 1")
        return {
            "stdout": _tail(self.stdout_path, lines),
            "stderr": _tail(self.stderr_path, lines),
        }

    def _require_running(self, status: LaunchAgentStatus) -> LaunchAgentStatus:
        if status.running and status.socket_available:
            return status
        raise LaunchAgentError(
            "LaunchAgent did not become available; inspect "
            f"`subauth daemon logs` or {self.stderr_path}"
        )

    async def _wait_for_status(
        self,
        *,
        running: bool,
        timeout: float = 5.0,
    ) -> LaunchAgentStatus:
        deadline = asyncio.get_running_loop().time() + timeout
        status = await self.status()
        while not _matches_requested_state(status, running) and (
            asyncio.get_running_loop().time() < deadline
        ):
            await asyncio.sleep(0.05)
            status = await self.status()
        return status

    async def _run(self, *arguments: str, check: bool = True) -> CommandResult:
        try:
            process = await asyncio.create_subprocess_exec(
                *self.launchctl,
                *arguments,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
        except FileNotFoundError as error:
            raise LaunchAgentError("launchctl is available only on macOS") from error
        stdout, stderr = await process.communicate()
        result = CommandResult(
            returncode=process.returncode or 0,
            stdout=stdout.decode(errors="replace"),
            stderr=stderr.decode(errors="replace"),
        )
        if check and result.returncode != 0:
            detail = result.stderr.strip() or result.stdout.strip() or "unknown error"
            raise LaunchAgentError(
                f"launchctl {' '.join(arguments)} failed ({result.returncode}): {detail}"
            )
        return result


async def socket_is_available(settings: Settings, timeout: float = 0.2) -> bool:
    try:
        reader, writer = await asyncio.wait_for(
            asyncio.open_unix_connection(str(settings.socket_path)),
            timeout=timeout,
        )
    except (ConnectionError, FileNotFoundError, OSError, TimeoutError):
        return False
    writer.close()
    await writer.wait_closed()
    del reader
    return True


def _tail(path: Path, lines: int) -> list[str]:
    try:
        content = path.read_text(errors="replace").splitlines()
    except FileNotFoundError:
        return []
    return content[-lines:]


def _launch_agent_settings(uid: int) -> Settings:
    runtime_dir = Path("/private/tmp") / f"subauth-{uid}"
    return Settings(runtime_dir=runtime_dir, socket_path=runtime_dir / "subauth.sock")


def _matches_requested_state(status: LaunchAgentStatus, running: bool) -> bool:
    if running:
        return status.running and status.socket_available
    return not status.running
