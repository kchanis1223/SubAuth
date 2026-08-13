# Gemini Antigravity official-runtime probe

Verified with Antigravity CLI 1.1.12 on macOS on 2026-08-13. The official
binary and its SHA-512 manifest were inspected before installation, then the
installed runtime was verified through a live OS-keyring login, model probe,
and subscription-backed text inference.

## Why Antigravity, not Gemini CLI

Google stopped serving Google AI Pro, Ultra, and individual free-tier requests
through Gemini CLI on 2026-06-18 and moved those tiers to Antigravity CLI. The
SubAuth consumer-login transport therefore resolves `agy` (or the equivalent
`antigravity` binary) and does not use the legacy Gemini CLI OAuth path.

Official references:

- <https://developers.googleblog.com/an-important-update-transitioning-gemini-cli-to-antigravity-cli/>
- <https://antigravity.google/docs/cli/install>
- <https://antigravity.google/docs/cli/headless>
- <https://antigravity.google/docs/cli/credits>
- <https://antigravity.google/terms>

## Runtime path

```text
SubAuth daemon -> isolated Antigravity CLI process -> OS keyring Google session
```

The runtime uses `agy models` as a non-inference eligibility probe. A successful
probe must return at least one model slug beginning with `gemini-`. Antigravity
does not expose an account-plan field, so this verifies runtime eligibility but
cannot distinguish Google AI Pro, Ultra, free, or an organization-managed
entitlement.

Every inference pins an advertised Gemini model and invokes:

```text
agy -p <prompt> --model <gemini-model> --output-format stream-json
    --print-timeout 5m --sandbox --disable-slash-commands
```

The process runs in an empty temporary workspace. SubAuth normalizes the
official NDJSON sequence:

```text
init -> step_update(agent_response.text_delta)* -> result
```

## Billing boundary

SubAuth removes API-key, ADC, Vertex, Cloud-project, and business-paygo
environment variables from each child process. It reads only the boolean credit
fallback setting from Antigravity's settings and refuses to run if either
`useG1Credits` or its legacy alias is true. This prevents the documented
fallback to purchased AI credits after plan quota exhaustion.

The keyring token, account identity, and Google email are never returned through
the SubAuth protocol.

## Tool isolation boundary

Antigravity's headless CLI does not currently offer a no-tools flag. The stream
`init` event explicitly lists available tools, and global MCP configuration may
also be loaded. SubAuth therefore:

- uses a fresh empty workspace for every request
- enables the native terminal sandbox
- disables slash commands
- prepends a text-only/no-tools instruction
- terminates the child process as soon as any `tool` step is observed

This limits impact but cannot guarantee that a tool has not begun executing
before its stream event arrives. External-user prompts remain out of scope until
Google provides a true tool-disable surface or a stronger isolated transport is
implemented.

## Policy boundary

Google documents Antigravity headless mode for scripts and CI, while the
Antigravity Additional Terms separately restrict access through third-party
software, tools, or services. SubAuth therefore labels this adapter
`terms-restricted` and `developer-controlled-evaluation-only`. It is not offered
as an external-demo or production transport without Google authorization.

## Verification state

| Capability | Result |
| --- | --- |
| Official CLI version and flags | Verified with temporary 1.1.12 binary |
| Google keyring login | Live verified |
| Gemini model filtering | Fixture-tested and live verified |
| API/Vertex/Cloud environment stripping | Fixture-tested |
| AI-credit fallback refusal | Fixture-tested |
| NDJSON normalization | Fixture-tested and live verified |
| Text delta streaming | Live verified |
| Tool-step termination | Fixture-tested |
| Exact subscription plan detection | Not exposed by runtime |

## Live smoke result

The live request pinned `gemini-3.6-flash-high`, produced `GEMINI_OK` followed
by a newline as normalized text deltas, and finished with
`response.completed`. Antigravity reported 18,410 input tokens, 70 output
tokens, and 62 thinking tokens for this minimal request.

The `init` event reported runtime tools as exposed even though none were used.
This confirms two V1 limitations: Antigravity is a comparatively heavy agent
harness for plain text inference, and the sandbox/stream guard is containment
rather than a true no-tools execution mode.
