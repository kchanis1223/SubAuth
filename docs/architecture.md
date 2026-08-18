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

## Request normalization

Spring AI messages are retained as structured runtime values until a provider
adapter renders them for its CLI:

- `RuntimeRole` preserves system, user, assistant, and tool roles;
- `RuntimeContent` distinguishes text, media, tool calls, and tool results;
- `RuntimeMessage` preserves the ordered content list and Spring AI message
  metadata;
- `RuntimeCapabilities` declares the roles, content types, effort values, and
  options each adapter can actually carry.

The current Codex, Claude Code, and Antigravity transports all declare a
text-only capability profile. Structured media and tool values exist in the
internal model so they can be implemented without redesigning the request
boundary, but are rejected before a CLI process starts today. Claude accepts
low through max effort except minimal; Antigravity accepts low, medium, and
high; Codex accepts the SubAuth effort vocabulary and leaves final
model/effort compatibility to its runtime.

Provider CLIs currently receive conversation history as a role-tagged text
rendering when they do not offer a native multi-message input. The structured
roles and metadata remain available to adapters, so a future runtime protocol
can consume them without changing the public Spring AI boundary.

## Response normalization

Provider runtime payloads remain private implementation details. Adapters map
them to `RuntimeEvent` values that keep response metadata, assistant-message
metadata, usage, generation index, and an observed finish reason separate.
`RuntimeResponseAccumulator` combines blocking-response deltas and creates the
same Spring AI `ChatResponse`, `Generation`, and `AssistantMessage` structures
used by official model implementations.

- response identifiers, selected model, usage, policy state, and transport
  details become `ChatResponseMetadata`;
- assistant-specific observable values become `AssistantMessage` properties;
- an observed provider stop reason becomes `ChatGenerationMetadata.finishReason`;
- missing model usage or finish reasons remain missing rather than being
  fabricated.

Streaming emits one `ChatResponse` per text delta and a final empty-content
response carrying completion metadata and usage. Callers consuming
`ChatClient.stream().content()` see the text deltas; callers consuming raw
`Flux<ChatResponse>` must allow for the final metadata-only response.

## Modules

- `subauth-spring-ai`: public model API and provider runtimes
- `subauth-spring-boot-autoconfigure`: properties, beans, and health
- `subauth-spring-boot-starter`: application-facing dependency

## ChatModel selection and coexistence

SubAuth auto-configuration is active only when
`spring.ai.model.chat=subauth`. It contributes the named
`subAuthChatModel` bean as the primary `ChatModel`, allowing Spring AI's normal
`ChatClient.Builder` auto-configuration to resolve it even when official or
custom model beans also exist. Backoff is scoped to an existing
`SubAuthChatModel`; an unrelated `ChatModel` no longer suppresses SubAuth.

This supports a configuration-only transport switch:

```text
development profile -> SubAuthChatModel -> subscription runtime
production profile  -> official ChatModel -> provider API
```

Application services remain coupled to `ChatClient`, `Prompt`, and
`ChatResponse`, not to either transport. Official provider dependencies and API
credentials belong only to the production profile. If applications manually
declare another primary `ChatModel`, they must resolve that intentional primary
conflict themselves or inject a model by qualifier.

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
