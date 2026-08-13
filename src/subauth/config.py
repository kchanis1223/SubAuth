from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def default_runtime_dir() -> Path:
    """Return a private, per-user runtime directory on macOS and other POSIX hosts."""
    override = os.environ.get("SUBAUTH_RUNTIME_DIR")
    if override:
        return Path(override).expanduser().resolve()
    return Path("/private/tmp") / f"subauth-{os.getuid()}"


@dataclass(frozen=True, slots=True)
class Settings:
    runtime_dir: Path
    socket_path: Path

    @classmethod
    def load(cls) -> Settings:
        runtime_dir = default_runtime_dir()
        socket_override = os.environ.get("SUBAUTH_SOCKET")
        socket_path = (
            Path(socket_override).expanduser().resolve()
            if socket_override
            else runtime_dir / "subauth.sock"
        )
        return cls(runtime_dir=runtime_dir, socket_path=socket_path)

