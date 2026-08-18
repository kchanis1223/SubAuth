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

## Stateless request lifecycle

Every streaming response has a client-generated request ID. The daemon tracks
the provider worker under that ID, allowing `responses.cancel` to stop it from a
second connection. Cancellation is normalized as `response.cancelled`.

SubAuth owns no application session or conversation history. The main service
owns user/session identity and builds every request from its canonical state.
Each request creates a fresh provider runtime context. Provider-native thread,
session, and conversation IDs may appear as diagnostic event metadata, but are
never accepted as continuation handles.

The only transient daemon state is an active provider task keyed by request ID.
It exists solely for cancellation and is removed when the request terminates.

## Provider-native event preservation

The stable response envelope is provider-neutral. The default `normalized`
mode contains only common lifecycle and text events. The opt-in
`normalized_with_native` mode adds the corresponding native Codex App Server,
Claude Code, or Antigravity event without replacing the common event type.
Native notifications with no common equivalent use the additive
`provider.event` type and never appear in default normalized streams.

Native data crosses a mandatory daemon redaction boundary. Credential-like
fields, account identity, local paths, plugin metadata, and MCP configuration
are replaced before the event reaches a client. Provider-native schemas remain
runtime-version-specific and are not part of SubAuth's portability guarantee.

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
