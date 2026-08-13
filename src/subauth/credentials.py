from __future__ import annotations

import asyncio
import shutil
import sys
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol


KEYCHAIN_SERVICE = "io.github.kchanis1223.subauth.credentials"


class CredentialVaultError(RuntimeError):
    """Raised when a credential vault operation cannot be completed."""


@dataclass(frozen=True, slots=True)
class CredentialRef:
    provider: str
    name: str
    environment_variable: str

    @property
    def account(self) -> str:
        return f"{self.provider}/{self.name}"

    @property
    def label(self) -> str:
        return f"SubAuth {self.provider} {self.name}"


CLAUDE_SETUP_TOKEN = CredentialRef(
    provider="claude",
    name="setup-token",
    environment_variable="CLAUDE_CODE_OAUTH_TOKEN",
)

KNOWN_CREDENTIALS = {(CLAUDE_SETUP_TOKEN.provider, CLAUDE_SETUP_TOKEN.name): CLAUDE_SETUP_TOKEN}


class CredentialVault(Protocol):
    @property
    def available(self) -> bool: ...

    async def contains(self, credential: CredentialRef) -> bool: ...

    async def get(self, credential: CredentialRef) -> str | None: ...

    async def store_interactive(self, credential: CredentialRef) -> None: ...

    async def delete(self, credential: CredentialRef) -> bool: ...


class NullCredentialVault:
    @property
    def available(self) -> bool:
        return False

    async def contains(self, credential: CredentialRef) -> bool:
        del credential
        return False

    async def get(self, credential: CredentialRef) -> str | None:
        del credential
        return None

    async def store_interactive(self, credential: CredentialRef) -> None:
        del credential
        raise CredentialVaultError("macOS Keychain is unavailable")

    async def delete(self, credential: CredentialRef) -> bool:
        del credential
        return False


class MacOSKeychainVault:
    """Small async wrapper around the macOS login Keychain."""

    _ITEM_NOT_FOUND = 44

    def __init__(self, command: Sequence[str] | None = None) -> None:
        executable = shutil.which("security") if command is None else None
        self._command = tuple(command) if command is not None else (
            (executable,) if executable else None
        )
        self._explicit_command = command is not None

    @property
    def available(self) -> bool:
        return self._command is not None and (
            sys.platform == "darwin" or self._explicit_command
        )

    async def contains(self, credential: CredentialRef) -> bool:
        result = await self._run(
            "find-generic-password",
            "-a",
            credential.account,
            "-s",
            KEYCHAIN_SERVICE,
            allow_not_found=True,
        )
        return result.returncode == 0

    async def get(self, credential: CredentialRef) -> str | None:
        result = await self._run(
            "find-generic-password",
            "-a",
            credential.account,
            "-s",
            KEYCHAIN_SERVICE,
            "-w",
            allow_not_found=True,
            secret_output=True,
        )
        if result.returncode == self._ITEM_NOT_FOUND:
            return None
        value = result.stdout.rstrip("\r\n")
        return value or None

    async def store_interactive(self, credential: CredentialRef) -> None:
        command = self._require_command()
        if not sys.stdin.isatty():
            raise CredentialVaultError(
                "credential storage requires an interactive terminal"
            )
        try:
            process = await asyncio.create_subprocess_exec(
                *command,
                "add-generic-password",
                "-a",
                credential.account,
                "-s",
                KEYCHAIN_SERVICE,
                "-l",
                credential.label,
                "-j",
                "Developer-only subscription credential managed by SubAuth",
                "-U",
                "-w",
            )
        except OSError as error:
            raise CredentialVaultError("Could not start the macOS Keychain tool") from error
        return_code = await process.wait()
        if return_code != 0:
            raise CredentialVaultError(
                f"macOS Keychain storage failed with exit status {return_code}"
            )

    async def delete(self, credential: CredentialRef) -> bool:
        result = await self._run(
            "delete-generic-password",
            "-a",
            credential.account,
            "-s",
            KEYCHAIN_SERVICE,
            allow_not_found=True,
        )
        return result.returncode == 0

    async def _run(
        self,
        *arguments: str,
        allow_not_found: bool = False,
        secret_output: bool = False,
    ) -> _CommandResult:
        command = self._require_command()
        try:
            process = await asyncio.create_subprocess_exec(
                *command,
                *arguments,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
        except OSError as error:
            raise CredentialVaultError("Could not start the macOS Keychain tool") from error
        stdout, stderr = await process.communicate()
        result = _CommandResult(
            returncode=process.returncode or 0,
            stdout=stdout.decode("utf-8", errors="replace"),
            stderr=stderr.decode("utf-8", errors="replace"),
        )
        if result.returncode == 0:
            return result
        if allow_not_found and result.returncode == self._ITEM_NOT_FOUND:
            return result
        detail = result.stderr.strip()
        if not detail and not secret_output:
            detail = result.stdout.strip()
        detail = detail or "unknown Keychain error"
        raise CredentialVaultError(
            f"macOS Keychain command failed ({result.returncode}): {detail}"
        )

    def _require_command(self) -> tuple[str, ...]:
        if not self.available or self._command is None:
            raise CredentialVaultError("macOS Keychain is unavailable")
        return self._command


@dataclass(frozen=True, slots=True)
class _CommandResult:
    returncode: int
    stdout: str
    stderr: str


def resolve_credential(provider: str, name: str) -> CredentialRef:
    try:
        return KNOWN_CREDENTIALS[(provider, name)]
    except KeyError as error:
        raise CredentialVaultError(f"unsupported credential: {provider}/{name}") from error


def default_credential_vault() -> CredentialVault:
    vault = MacOSKeychainVault()
    return vault if vault.available else NullCredentialVault()
