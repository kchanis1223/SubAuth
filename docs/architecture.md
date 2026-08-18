# Architecture

## Product boundary

SubAuth is a Spring AI model provider for development and developer-controlled
demos. The Spring Boot main service remains responsible for HTTP endpoints,
external-user authentication, authorization, rate limits, persistence, and
chat memory.

```text
external user
    -> Spring Boot main service
        -> Spring AI ChatClient
            -> SubAuthChatModel
                -> subscription runtime
```

SubAuth never accepts requests directly from external users.

## In-process design

`SubAuthChatModel` implements Spring AI's `ChatModel` and
`StreamingChatModel` contract. There is no sidecar daemon, local HTTP gateway,
Unix socket, custom client SDK, or Python runtime.

1. Spring AI builds a `Prompt`, including history supplied by chat memory.
2. `PromptRuntimeMapper` validates supported options and creates a stateless
   `RuntimeRequest`.
3. `RuntimeRegistry` selects the configured provider adapter.
4. The adapter invokes Codex App Server, Claude Code, or Antigravity.
5. Runtime events are converted directly to Spring AI `ChatResponse` values.
6. Reactor cancellation interrupts the turn or child process.

## Modules

- `subauth-spring-ai`: public model API and provider runtimes
- `subauth-spring-boot-autoconfigure`: properties, beans, and health
- `subauth-spring-boot-starter`: application-facing dependency

Provider runtime formats are private implementation details. No native runtime
payload is exposed as a second public protocol. Observable provider-specific
values are attached to Spring AI response metadata only when they are safe and
well-defined.

## State ownership

SubAuth is stateless at the application level. It does not accept native thread,
session, or conversation identifiers as continuation handles. Spring AI
`ChatMemory` or the main service database owns canonical conversation history.

Codex App Server is a long-lived process owned by the application context, but
each inference uses an ephemeral thread. Claude Code and Antigravity use an
isolated temporary workspace per request.

## Security boundary

- Provider API credential variables are removed before subscription CLIs run.
- Codex threads are ephemeral, read-only, and use approval policy `never`.
- Claude tools, MCP configuration, slash commands, and session persistence are
  disabled.
- Antigravity runs sandboxed in a temporary workspace and is terminated on tool
  use.
- Prompt and response bodies are not logged by SubAuth.
- Runtime stderr is not relayed to callers because it can contain secrets.

The application process and provider CLIs run as the same macOS user. The main
service therefore remains inside the developer's local trust boundary.
