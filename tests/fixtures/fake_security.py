from __future__ import annotations

import os
import sys


args = sys.argv[1:]
command = args[0] if args else ""
value = os.environ.get("FAKE_KEYCHAIN_VALUE")

if command == "find-generic-password":
    if os.environ.get("FAKE_KEYCHAIN_ERROR"):
        print(value or "secret-error-output")
        raise SystemExit(2)
    if value is None:
        raise SystemExit(44)
    if "-w" in args:
        print(value)
    else:
        print('keychain: "/fake/login.keychain-db"')
    raise SystemExit(0)

if command == "delete-generic-password":
    raise SystemExit(0 if value is not None else 44)

if command == "add-generic-password":
    raise SystemExit(0)

raise SystemExit(2)
