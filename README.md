# SubAuth

SubAuth is a local sidecar daemon that lets an AI service under development use
the developer's own AI subscriptions. The first providers are OpenAI, Claude,
and Gemini.

The main service sends provider-neutral requests to SubAuth. SubAuth owns the
developer's credentials, selects an official runtime or a direct subscription
adapter, and returns normalized streaming events.

```text
main service -> SubAuth -> provider runtime or protocol
main service <- SubAuth <- provider runtime or protocol
```

## Project status

The Python daemon foundation, OpenAI subscription transport, experimental
Claude subscription transport, and terms-restricted Gemini/Antigravity
transport are implemented. See
[`docs/implementation-plan.md`](docs/implementation-plan.md) for the staged
roadmap.

## Development

Python 3.12 or newer is required. The current foundation uses only the standard
library, so it can be exercised without installing project dependencies. The
OpenAI transport requires a current Codex CLI on `PATH`; the Claude transport
requires a current Claude Code CLI on `PATH`.

```bash
python3 -m unittest discover -s tests -v
PYTHONPATH=src python3 -m subauth serve
```

### macOS background service

Instead of keeping `serve` open in a terminal, install the per-user LaunchAgent:

```bash
PYTHONPATH=src python3 -m subauth daemon install
PYTHONPATH=src python3 -m subauth daemon status
```

The job is loaded into the current user's `launchd` GUI domain and runs in that
same user context, so the official provider CLIs retain access to their normal
macOS Keychain sessions. The plist stores executable paths and configuration
only; it does not copy provider credentials. `RunAtLoad` and `KeepAlive` are
disabled. Any normal SubAuth client request starts the installed job on demand
if its Unix socket is unavailable.

Management commands are:

```bash
PYTHONPATH=src python3 -m subauth daemon start
PYTHONPATH=src python3 -m subauth daemon stop
PYTHONPATH=src python3 -m subauth daemon restart
PYTHONPATH=src python3 -m subauth daemon logs --lines 100
PYTHONPATH=src python3 -m subauth daemon uninstall
```

The generated LaunchAgent points at the current checkout and Python
interpreter. Re-run `daemon install` after moving the repository or changing
Python installations. Environment overrides such as `SUBAUTH_RUNTIME_DIR` and
`SUBAUTH_SOCKET` intentionally disable automatic LaunchAgent startup so tests
cannot accidentally contact the developer's real daemon.

In another terminal:

```bash
PYTHONPATH=src python3 -m subauth status
PYTHONPATH=src python3 -m subauth providers
PYTHONPATH=src python3 -m subauth probe openai
PYTHONPATH=src python3 -m subauth probe claude
```

`probe openai` starts Codex App Server, reads the current account and model
catalog through its official JSON-RPC interface, and reports whether a ChatGPT
subscription is ready. It does not make a model inference request. If signed
out, keep the daemon running and start the managed browser flow with:

```bash
PYTHONPATH=src python3 -m subauth login openai
```

Raw OpenAI access and refresh tokens are never read or returned by SubAuth.

To make an explicit subscription-backed inference request, keep the daemon
running and use:

```bash
PYTHONPATH=src python3 -m subauth run openai "Reply exactly: SUBAUTH_OK"
```

The command emits protocol-v1 JSON events as they arrive, including
`response.started`, `output.text.delta`, and `response.completed`. This command
does consume the developer's ChatGPT subscription allowance.

The daemon listens on a per-user Unix domain socket by default. It does not
perform end-user access control; the calling main service owns that concern.

## Claude (experimental)

Claude runs through the official Claude Code CLI with tools, MCP servers,
session persistence, and filesystem writes disabled. SubAuth removes API-key,
cloud-provider, and federation credential variables from every Claude child
process so this transport cannot silently fall back to usage-based API billing.

Use the normal Claude.ai browser login:

```bash
PYTHONPATH=src python3 -m subauth login claude
PYTHONPATH=src python3 -m subauth run claude "Reply exactly: CLAUDE_OK"
```

SubAuth also recognizes the official `claude setup-token` flow. Generate the
token outside SubAuth, export it as `CLAUDE_CODE_OAUTH_TOKEN` before starting a
foreground daemon, and then use the same `probe` and `run` commands. SubAuth
checks only that the variable is present; it never returns or persists the
token. The LaunchAgent deliberately does not copy shell-only secrets into its
plist. Use normal Claude.ai stored login for background-service operation, or
store a setup token in the SubAuth Keychain vault:

```bash
PYTHONPATH=src python3 -m subauth credential set claude setup-token
PYTHONPATH=src python3 -m subauth credential status claude setup-token
```

`credential set` delegates input to the macOS Keychain password prompt. The
token is not placed in a command-line argument, plist, log, or SubAuth protocol
message. The daemon reads it immediately before launching Claude Code and
injects it only into that isolated child process. Remove it with:

```bash
PYTHONPATH=src python3 -m subauth credential delete claude setup-token
```

The Keychain vault does not change Anthropic's policy boundary: setup-token
routing remains experimental and limited to development or small previews.

> **Policy warning:** Anthropic says product and service developers should use
> API-key or supported cloud-provider authentication and does not permit
> third-party routing through consumer Claude plan credentials. This adapter is
> therefore marked `experimental` and `development-and-limited-preview-only`.
> The CLI prints this warning before login and inference, and every normalized
> response carries it as policy metadata. Migrate to the Claude API before a
> formal release. See the
> [Anthropic legal and compliance documentation](https://code.claude.com/docs/en/legal-and-compliance).

The verified runtime contract and limitations are recorded in
[`docs/probes/claude.md`](docs/probes/claude.md).

## Gemini via Antigravity (terms-restricted)

Google moved personal Google AI Pro, Ultra, and free-tier terminal access from
Gemini CLI to Antigravity CLI in June 2026. SubAuth therefore targets the
official `agy` runtime rather than the legacy `gemini` executable.

Install Antigravity CLI using Google's current instructions, then launch `agy`
once in an interactive terminal to complete Google sign-in. The runtime stores
the session in the OS-native keyring.

```bash
PYTHONPATH=src python3 -m subauth login gemini
PYTHONPATH=src python3 -m subauth probe gemini
PYTHONPATH=src python3 -m subauth run gemini "Reply exactly: GEMINI_OK"
```

SubAuth strips Gemini API-key, ADC, Vertex, and Cloud-project environment
variables. It also refuses to run when Antigravity's `useG1Credits` fallback is
enabled, because that setting can consume purchased AI credits after plan quota
is exhausted. The CLI does not expose the account's exact plan tier, so SubAuth
can verify only that the keyring-authenticated runtime offers Gemini models.

Antigravity does not currently expose a flag that removes all built-in and MCP
tools. SubAuth runs each request in an empty temporary workspace with terminal
sandboxing and slash commands disabled, asks the model not to use tools, and
terminates the request if a tool step appears. This is a containment boundary,
not a proof that tools are absent.

> **Terms warning:** Google Antigravity's additional terms restrict accessing
> the service with third-party software. This adapter is marked
> `terms-restricted` and `developer-controlled-evaluation-only`. Do not use it
> for external-user demonstrations or production traffic without Google
> authorization. See the
> [Antigravity terms](https://antigravity.google/terms).

See [`docs/probes/gemini.md`](docs/probes/gemini.md) for the verified contract.
The live smoke test completed with `GEMINI_OK` on `gemini-3.6-flash-high`.
Antigravity exposed its agent tool surface and consumed 18,410 input tokens for
that minimal request, so this transport is substantially heavier than a direct
text API and remains unsuitable for high-volume use.

## Logs and credential safety

Daemon logs are newline-delimited JSON and contain lifecycle, request ID,
method, provider, completion, and error metadata. Prompt text and model output
are not logged. Sensitive field names, bearer values, token assignments, and
known runtime credentials are redacted before formatting. View the current log
tail with `subauth daemon logs`; provider credentials are never intentionally
included in client responses or logs.

## Python SDK

SubAuth exposes a typed asynchronous Python SDK while keeping the low-level
Unix-socket client available. Run source-tree examples with `PYTHONPATH=src`:

```python
import asyncio
from subauth import AsyncSubAuth

async def main():
    client = AsyncSubAuth()
    result = await client.responses.create(
        provider="openai",
        input="Reply exactly: SDK_OK",
    )
    print(result.text)

asyncio.run(main())
```

Streaming returns a `ResponseStream` whose request ID is available before the
request starts. Calling `cancel()` sends cancellation over a separate socket
and stops the active provider worker:

```python
stream = client.responses.stream(provider="openai", input="Explain in detail")
async with stream:
    async for event in stream:
        if event.type == "output.text.delta":
            print(event.data["delta"], end="")
        if should_stop:
            await stream.cancel()
```

OpenAI supports resumable common sessions through Codex threads:

```python
session = await client.sessions.create(
    provider="openai",
    model="auto",
    system="Be concise.",
)
await client.responses.create(provider="openai", input="Remember 17", session=session)
answer = await client.responses.create(
    provider="openai",
    input="What number did I ask you to remember?",
    session=session,
)
```

Sessions currently live in daemon memory and are invalidated by daemon restart.
Claude and Gemini advertise `sessions: false`, so session creation for those
transports returns `sessions_not_supported` instead of simulating continuity.
See [`examples/python_sdk.py`](examples/python_sdk.py) and
[`docs/sdk.md`](docs/sdk.md).
