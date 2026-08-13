# Python SDK and common request lifecycle

## Public entry point

`subauth.AsyncSubAuth` is the typed asynchronous SDK. It connects to the same
per-user Unix socket as the CLI and lazily starts an installed LaunchAgent.

```python
from subauth import AsyncSubAuth

client = AsyncSubAuth()
```

The SDK has two resources:

- `client.responses`: create, stream, and cancel response requests
- `client.sessions`: create, retrieve, list, and delete common sessions

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

## Sessions

Common sessions have a SubAuth ID and may bind a provider-native session ID.
They can carry default model and system instruction values. The system text is
kept inside the daemon and is represented in session metadata only as
`has_system_instruction`.

For OpenAI, the first turn binds the common session to an ephemeral Codex thread
and later turns target that active thread in the same App Server process. A
session allows one active request at a time; parallel turns return
`session_busy`.

Current session storage is intentionally in-memory:

- daemon restart invalidates all common session IDs
- no conversation content is written by SubAuth
- provider runtimes may apply their own storage behavior
- Claude and Gemini session creation is rejected until their isolated runtime
  contracts can provide genuine resume semantics

## Raw protocol methods

| Method | Purpose |
| --- | --- |
| `responses.create` | Stream a normalized provider response |
| `responses.cancel` | Cancel an active request by request ID |
| `sessions.create` | Create a provider-backed common session |
| `sessions.get` | Retrieve session metadata |
| `sessions.list` | List daemon-memory sessions |
| `sessions.delete` | Delete an inactive session |

The protocol remains version 1. These methods extend the method surface without
changing JSON framing.
