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
| Tool callbacks | Not yet supported |
| Media and files | Not yet supported |
| Structured output | Not yet supported |
| Temperature, top-p, penalties, stop sequences | Rejected |
| Native provider session continuation | Intentionally unsupported |

Unsupported request options raise `SubAuthUnsupportedCapabilityException`.
SubAuth does not fabricate missing usage or finish-reason data. When a runtime
does not reveal its effective model, the configured model is used, or `auto`
is reported when runtime selection was requested.

Provider adapters expose `RuntimeCapabilities`. Prompt options, effort values,
media, assistant tool calls, and tool-result messages are checked against the
selected adapter before subscription authentication or inference begins.

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
