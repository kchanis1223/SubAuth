from __future__ import annotations

import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from subauth.credentials import (  # noqa: E402
    CLAUDE_SETUP_TOKEN,
    CredentialVaultError,
    MacOSKeychainVault,
    resolve_credential,
)

FIXTURE = Path(__file__).parent / "fixtures" / "fake_security.py"


class CredentialVaultTests(unittest.IsolatedAsyncioTestCase):
    def vault(self) -> MacOSKeychainVault:
        return MacOSKeychainVault((sys.executable, str(FIXTURE)))

    async def test_reads_known_credential_without_returning_metadata(self) -> None:
        with patch.dict(os.environ, {"FAKE_KEYCHAIN_VALUE": "secret-vault-token"}):
            vault = self.vault()

            self.assertTrue(await vault.contains(CLAUDE_SETUP_TOKEN))
            self.assertEqual(await vault.get(CLAUDE_SETUP_TOKEN), "secret-vault-token")

    async def test_missing_credential_returns_none(self) -> None:
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("FAKE_KEYCHAIN_VALUE", None)
            vault = self.vault()

            self.assertFalse(await vault.contains(CLAUDE_SETUP_TOKEN))
            self.assertIsNone(await vault.get(CLAUDE_SETUP_TOKEN))
            self.assertFalse(await vault.delete(CLAUDE_SETUP_TOKEN))

    async def test_secret_stdout_is_not_included_in_errors(self) -> None:
        with patch.dict(
            os.environ,
            {
                "FAKE_KEYCHAIN_VALUE": "secret-error-output",
                "FAKE_KEYCHAIN_ERROR": "1",
            },
        ):
            with self.assertRaises(CredentialVaultError) as raised:
                await self.vault().get(CLAUDE_SETUP_TOKEN)

        self.assertNotIn("secret-error-output", str(raised.exception))

    def test_resolves_only_registered_credentials(self) -> None:
        self.assertIs(resolve_credential("claude", "setup-token"), CLAUDE_SETUP_TOKEN)
        with self.assertRaises(CredentialVaultError):
            resolve_credential("openai", "refresh-token")


if __name__ == "__main__":
    unittest.main()
