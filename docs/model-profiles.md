# Planned provider model and effort profiles

This is the next configuration phase after the Python SDK and request lifecycle.
The goal is to choose model and reasoning effort once during initial setup,
rather than requiring every application request to contain provider-specific
values.

## Proposed precedence

```text
request override
  > named application profile
  > provider default profile
  > official runtime default
```

Fallback to another model or effort must never be silent. The normalized
response should report requested and effective values plus any fallback reason.

## Current runtime mapping

| Provider | Model control | Effort control | Next adapter mapping |
| --- | --- | --- | --- |
| OpenAI | Codex model IDs | Codex turn `effort` | model profile to thread, effort to turn |
| Claude | Claude Code `--model` | Claude Code `--effort` | both flags per isolated request |
| Gemini | Antigravity `--model` | Antigravity `--effort` | both flags per isolated request |

The setup command should probe each installed runtime first and offer only
models and effort values advertised or accepted by that runtime. Configuration
must store identifiers and policy metadata, never credentials.

## Proposed configuration surface

```text
subauth configure
subauth config show
subauth config set-default openai --model <id> --effort high
subauth config set-default claude --model <id> --effort medium
subauth config set-default gemini --model <id> --effort low
```

The future config should support named profiles such as `fast`, `balanced`, and
`deep`, while provider defaults remain explicit. Validation, effective-value
metadata, and conformance tests should be implemented together so an unsupported
effort never gets ignored silently.
