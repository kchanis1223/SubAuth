from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import signal
import sys
import webbrowser
from collections.abc import Sequence

from subauth.client import SubAuthClient
from subauth.config import Settings
from subauth.daemon.server import SubAuthDaemon
from subauth.launchd import LaunchAgentError, LaunchAgentManager
from subauth.providers.claude import CLAUDE_POLICY_WARNING
from subauth.providers.gemini import GEMINI_POLICY_WARNING


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="subauth")
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("serve", help="run the local SubAuth daemon in the foreground")
    subparsers.add_parser("status", help="check whether the local daemon is available")
    subparsers.add_parser("providers", help="show provider adapter status")

    daemon = subparsers.add_parser("daemon", help="manage the macOS LaunchAgent")
    daemon_commands = daemon.add_subparsers(dest="daemon_command", required=True)
    for command in ("install", "uninstall", "start", "stop", "restart", "status"):
        daemon_commands.add_parser(command)
    logs = daemon_commands.add_parser("logs")
    logs.add_argument("--lines", type=int, default=100)

    probe = subparsers.add_parser("probe", help="inspect one provider runtime and login state")
    probe.add_argument("provider", choices=("openai", "claude", "gemini"))

    login = subparsers.add_parser("login", help="start a provider login flow")
    login.add_argument("provider", choices=("openai", "claude", "gemini"))

    run = subparsers.add_parser("run", help="stream one prompt through a provider")
    run.add_argument("provider", choices=("openai", "claude", "gemini"))
    run.add_argument("prompt")
    run.add_argument("--model", default="auto")
    run.add_argument("--system")
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


async def _login(provider: str) -> int:
    _warn_provider_policy(provider)
    settings = Settings.load()
    try:
        response = await SubAuthClient(settings).request(
            "providers.login", {"provider": provider}
        )
    except (ConnectionError, FileNotFoundError, OSError) as error:
        print(f"SubAuth daemon is unavailable: {error}", file=sys.stderr)
        return 1
    result = response.get("result")
    login = result.get("metadata", {}).get("login", {}) if isinstance(result, dict) else {}
    url = login.get("auth_url") or login.get("verification_url")
    if isinstance(url, str) and url:
        opened = webbrowser.open(url)
        if not opened:
            print(f"Open this URL to continue login: {url}", file=sys.stderr)
        user_code = login.get("user_code")
        if user_code:
            print(f"Device code: {user_code}", file=sys.stderr)
    command = login.get("command")
    if isinstance(command, str) and command:
        print(f"Run this command in an interactive terminal: {command}", file=sys.stderr)
        detail = login.get("detail")
        if isinstance(detail, str) and detail:
            print(detail, file=sys.stderr)
    print(json.dumps(response, ensure_ascii=False, indent=2))
    return 0 if response.get("error") is None else 1


async def _run(provider: str, prompt: str, model: str, system: str | None) -> int:
    _warn_provider_policy(provider)
    params = {"provider": provider, "input": prompt, "model": model}
    if system:
        params["system"] = system
    failed = False
    try:
        async for event in SubAuthClient(Settings.load()).stream("responses.create", params):
            print(json.dumps(event, ensure_ascii=False))
            if event.get("type") == "response.failed" or event.get("error") is not None:
                failed = True
    except (ConnectionError, FileNotFoundError, OSError) as error:
        print(f"SubAuth daemon is unavailable: {error}", file=sys.stderr)
        return 1
    return 1 if failed else 0


async def _daemon(command: str, lines: int = 100) -> int:
    manager = LaunchAgentManager()
    try:
        if command == "install":
            status = await manager.install()
        elif command == "uninstall":
            status = await manager.uninstall()
        elif command == "start":
            status = await manager.start()
        elif command == "stop":
            status = await manager.stop()
        elif command == "restart":
            status = await manager.restart()
        elif command == "status":
            status = await manager.status()
        elif command == "logs":
            print(json.dumps(manager.read_logs(lines), ensure_ascii=False, indent=2))
            return 0
        else:
            raise AssertionError(f"Unhandled daemon command: {command}")
    except (LaunchAgentError, ValueError) as error:
        print(f"SubAuth daemon command failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(status.to_dict(), ensure_ascii=False, indent=2))
    if command == "status":
        return 0 if status.installed and status.loaded else 1
    return 0


def _warn_provider_policy(provider: str) -> None:
    if provider == "claude":
        print(f"WARNING: {CLAUDE_POLICY_WARNING}", file=sys.stderr)
    elif provider == "gemini":
        print(f"WARNING: {GEMINI_POLICY_WARNING}", file=sys.stderr)


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "serve":
        return asyncio.run(_serve())
    if args.command == "status":
        return asyncio.run(_request("system.ping"))
    if args.command == "providers":
        return asyncio.run(_request("providers.list"))
    if args.command == "daemon":
        return asyncio.run(_daemon(args.daemon_command, getattr(args, "lines", 100)))
    if args.command == "probe":
        return asyncio.run(_request("providers.probe", {"provider": args.provider}))
    if args.command == "login":
        return asyncio.run(_login(args.provider))
    if args.command == "run":
        return asyncio.run(_run(args.provider, args.prompt, args.model, args.system))
    raise AssertionError(f"Unhandled command: {args.command}")
