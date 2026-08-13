from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import signal
import sys
from collections.abc import Sequence

from subauth.client import SubAuthClient
from subauth.config import Settings
from subauth.daemon.server import SubAuthDaemon


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="subauth")
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("serve", help="run the local SubAuth daemon in the foreground")
    subparsers.add_parser("status", help="check whether the local daemon is available")
    subparsers.add_parser("providers", help="show provider adapter status")

    login = subparsers.add_parser("login", help="start a provider login flow")
    login.add_argument("provider", choices=("openai", "claude", "gemini"))
    return parser


async def _serve() -> int:
    daemon = SubAuthDaemon()
    stop_requested = asyncio.Event()
    loop = asyncio.get_running_loop()
    for name in ("SIGINT", "SIGTERM"):
        with contextlib.suppress(NotImplementedError):
            loop.add_signal_handler(getattr(signal, name), stop_requested.set)
    try:
        await daemon.start()
        print(f"SubAuth listening on {daemon.settings.socket_path}", flush=True)
        await stop_requested.wait()
    finally:
        await daemon.close()
    return 0


async def _request(method: str, params: dict[str, str] | None = None) -> int:
    settings = Settings.load()
    try:
        response = await SubAuthClient(settings).request(method, params)
    except (ConnectionError, FileNotFoundError, OSError) as error:
        print(f"SubAuth daemon is unavailable: {error}", file=sys.stderr)
        return 1
    print(json.dumps(response, ensure_ascii=False, indent=2))
    return 0 if response.get("error") is None else 1


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "serve":
        return asyncio.run(_serve())
    if args.command == "status":
        return asyncio.run(_request("system.ping"))
    if args.command == "providers":
        return asyncio.run(_request("providers.list"))
    if args.command == "login":
        return asyncio.run(_request("providers.login", {"provider": args.provider}))
    raise AssertionError(f"Unhandled command: {args.command}")
