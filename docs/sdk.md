# Python SDK and stateless request lifecycle

## Public entry point

`subauth.AsyncSubAuth` is the typed asynchronous SDK. It connects to the same
per-user Unix socket as the CLI and lazily starts an installed LaunchAgent.

```python
from subauth import AsyncSubAuth

client = AsyncSubAuth()
```

The SDK has one resource:

- `client.responses`: create, stream, and cancel response requests

`SubAuthClient` remains the low-level protocol client for integrations that
need raw dictionaries.

## Response lifecycle

`responses.stream()` returns a `ResponseStream`. Its `request_id` is allocated
before network I/O, so another coroutine can cancel the request while the
stream is active. Normal terminal events are:

- `response.completed`
- `response.failed`
- `response.cancelled`

Breaking out of a stream inside `async with` requests cancellation and closes
the socket. OpenAI cancellation also sends Codex App Server `turn/interrupt`.
Claude Code and Antigravity child processes are terminated by their async
generator cleanup.

`responses.create()` consumes the same stream, concatenates text deltas, and
returns a typed `ResponseResult`. A provider failure raises `SubAuthAPIError`
with a stable SubAuth error code.

## Response modes

`response_mode="normalized"` is the default and preserves the original compact
protocol. Events contain only the stable `request_id`, `type`, and `data`
fields.

`response_mode="normalized_with_native"` adds a typed `ResponseEvent.native`
mapping. It contains `runtime` and the matching provider `event`, while the
normal `type` and `data` fields continue to drive portable application logic.
The daemon redacts credentials, account identity, local paths, plugins, and MCP
configuration before serialization.

Provider notifications without a common SubAuth equivalent are emitted as
`provider.event` only in this opt-in mode. Their `data.native_type` supports
lightweight filtering, while the complete sanitized payload remains under
`native.event`.

```python
stream = client.responses.stream(
    provider="claude",
    input="Reply briefly",
    response_mode="normalized_with_native",
)
async for event in stream:
    if event.type == "output.text.delta":
        print(event.data["delta"], end="")
    if event.native is not None:
        inspect_provider_event(event.native)
```

SubAuth intentionally does not expose an unwrapped `raw` mode. Provider-native
schemas do not share terminal or cancellation semantics and may contain local
runtime details. Unsupported values return `invalid_response_mode`.

## Stateless contract

SubAuth does not create or retain application sessions, conversation history,
or resumable provider handles. The main service owns user/session identity and
its canonical conversation history. For a multi-turn experience, it constructs
each input from the context that should be visible to that turn.

Each `responses.create` call starts a fresh provider runtime context. A provider
may still report a thread, session, or conversation ID in event metadata. Such
IDs exist for diagnostics only; SubAuth never accepts them as continuation
handles. Supplying `session_id`, `provider_session_id`,
`thread_id`, or `conversation_id` to `responses.create` returns
`stateful_context_not_supported`. The daemon retains only an active task keyed
by `request_id`, and removes it at the terminal event.

## Raw protocol methods

| Method | Purpose |
| --- | --- |
| `responses.create` | Stream a normalized response with optional sanitized native data |
| `responses.cancel` | Cancel an active request by request ID |

The protocol remains version 1. These methods extend the method surface without
changing JSON framing.
