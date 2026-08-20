# Spring AI compatibility

## Tested baselines

| Spring Boot | Spring AI | Status |
|---|---|---|
| 3.5.16 | 1.1.8 | Supported and CI-tested |
| 4.1.0 | 2.0.0 | Supported and CI-tested |

The host application's Spring AI BOM remains authoritative. SubAuth is binary
compatible with both tested API generations and does not require a framework
upgrade. The optional Boot 4 health integration is not added to Boot 3 consumer
classpaths.

SubAuth uses explicit versions for its direct build dependencies. Its published
parent and module POMs do not import the Spring Boot or Spring AI BOMs. This
prevents a Boot 3 consumer from resolving the complete Boot 4 dependency
management graph just to read SubAuth metadata. The host application's platform
can still align SubAuth's transitive Spring dependencies to its selected Spring
AI generation.

## Supported surface

| Spring AI surface | Status |
|---|---|
| `ChatModel.call(Prompt)` | Supported |
| `StreamingChatModel.stream(Prompt)` | Supported |
| `ChatClient.call()` | Supported |
| `ChatClient.stream()` | Supported |
| System/user/assistant text messages | Supported |
| Structured internal message roles and metadata | Supported |
| Chat history supplied in `Prompt` | Supported |
| `ChatResponse` model and metadata | Supported |
| Usage metadata | Best effort |
| Provider-observed finish reason | Best effort |
| Separate response/message metadata | Supported |
| Reactor cancellation | Supported |
| Provider/model/effort selection | Supported |
| Coexistence with other `ChatModel` beans | Supported |
| Configuration-only official-provider switch | Supported |
| Spring AI tool callbacks through Codex | Supported; request-scoped execution |
| Claude/Gemini tool callbacks | Not yet supported |
| PNG/JPEG `Media` input through Codex | Supported |
| Claude/Gemini media, audio, and general files | Not yet supported |
| Structured output | Not yet supported |
| Temperature, top-p, penalties, token limits, stop sequences | Ignored by default; configurable as warn or reject |
| Native provider session continuation | Intentionally unsupported |

Portable generation options that a subscription runtime cannot carry are
ignored by default so an existing Spring AI service can still be exercised.
Their Spring AI names are exposed in `ChatResponseMetadata.ignoredOptions`.
Set `spring.ai.subauth.unsupported-options` to `warn` to also log a warning, or
to `reject` to raise `SubAuthUnsupportedCapabilityException` as before.
Capability mismatches that change request meaning, including unsupported media,
files, and tool calls, are always rejected. Codex image input accepts at most
four PNG/JPEG images per request, 10 MiB per image and 20 MiB total. Codex tool
callbacks use App Server dynamic tools, share the request timeout, and are
limited to eight calls per request. Provider-native assistant tool-call and
tool-result history is not yet accepted.

SubAuth does not fabricate missing usage or finish-reason data. When a runtime
does not reveal its effective model, the configured model is used, or `auto`
is reported when runtime selection was requested.

Provider adapters expose `RuntimeCapabilities`. Prompt options, effort values,
media, assistant tool calls, and tool-result messages are checked against the
selected adapter before subscription authentication or inference begins.
Codex additionally checks the selected model's advertised input modalities.

## Effort

Claude Code accepts `low`, `medium`, `high`, `xhigh`, and `max`. Antigravity
accepts `low`, `medium`, and `high`; other values are rejected. Codex receives
the selected value through its app-server thread configuration and the runtime
is authoritative about whether the selected model accepts it.

## Provider-specific Spring AI classes

Applications should use portable `ChatClient`, `Prompt`, `ChatResponse`, and
`ChatMemory` APIs. Code that constructs `OpenAiChatOptions`,
`AnthropicChatOptions`, or `GoogleGenAiChatOptions` must move to
`SubAuthChatOptions` for provider, model, and effort selection.
