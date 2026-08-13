# Architecture

## Boundary

SubAuth is a developer-sponsored subscription runtime. It is not an end-user
authentication system.

```text
external user -> main service -> SubAuth -> AI provider
external user <- main service <- SubAuth <- AI provider
```

The main service decides who may access the product. Once a request reaches the
local SubAuth socket, SubAuth executes it when the selected provider is usable.

## Components

1. The daemon owns the Unix socket and provider worker lifecycle.
2. The credential broker stores provider credentials in macOS Keychain.
3. The capability router selects an official runtime, direct subscription
   transport, or API transport.
4. Provider adapters convert common requests and events.
5. The client SDK hides daemon startup and wire-protocol details.

## Request lifecycle and sessions

Every streaming response has a client-generated request ID. The daemon tracks
the provider worker under that ID, allowing `responses.cancel` to stop it from a
second connection. Cancellation is normalized as `response.cancelled`.

Common sessions are daemon-memory records that bind a SubAuth session ID to a
provider-native session ID. They carry optional model and system defaults and
permit one active turn at a time. OpenAI binds sessions to Codex threads and
resumes them on later turns. Claude and Gemini reject session creation until
their isolated transports support genuine continuation.

## Credential broker

Provider-owned stored login remains the first choice for official runtimes. For
credentials that must otherwise live in a shell environment, the credential
broker stores a generic-password item in the current user's macOS Keychain.
Items use service `io.github.kchanis1223.subauth.credentials` and a non-secret
provider/credential account name.

Interactive storage is delegated to the macOS `security` prompt so credential
data does not appear in SubAuth command arguments. The daemon retrieves a value
only when preparing the matching provider child environment. Plists, protocol
messages, status output, and structured logs expose presence and storage type
only. The first implemented broker entry is Claude `setup-token`.

## Transport preference

Every provider can implement these transports:

1. `official-runtime`
2. `direct-subscription`
3. `api`

`auto` selects the first transport that satisfies all requested capabilities.
The actual transport and its support level must be returned in response
metadata; fallback must never be silent.

The initial `run` command is explicitly subscription-backed. If a provider
runtime reports API-key authentication, SubAuth returns
`subscription_not_ready` instead of silently consuming API credits.

## Security boundary

The initial daemon binds only to a per-user Unix domain socket. It does not
listen on TCP. Credentials never appear in main-service responses, logs, or
provider-neutral events. Local shell and filesystem tools are outside the V1
daemon boundary.

Daemon logs are newline-delimited JSON. They record request identifiers,
methods, provider selection, lifecycle, completion, and sanitized errors, but
not prompt or response content. Sensitive keys and recognizable credential
assignments are redacted before serialization.
