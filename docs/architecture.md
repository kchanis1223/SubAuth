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

## Transport preference

Every provider can implement these transports:

1. `official-runtime`
2. `direct-subscription`
3. `api`

`auto` selects the first transport that satisfies all requested capabilities.
The actual transport and its support level must be returned in response
metadata; fallback must never be silent.

## Security boundary

The initial daemon binds only to a per-user Unix domain socket. It does not
listen on TCP. Credentials never appear in main-service responses, logs, or
provider-neutral events. Local shell and filesystem tools are outside the V1
daemon boundary.

