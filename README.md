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

The Python daemon foundation, OpenAI subscription transport, and experimental
Claude subscription transport are implemented. Gemini remains a provider
contract pending its capability probe. See
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
token outside SubAuth, export it as `CLAUDE_CODE_OAUTH_TOKEN` before starting
the daemon, and then use the same `probe` and `run` commands. SubAuth checks only
that the variable is present; it never returns or persists the token. Native
Keychain import and daemon installation are later phases.

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
