# Spring AI compatibility

## Supported surface

| Spring AI surface | Status |
|---|---|
| `ChatModel.call(Prompt)` | Supported |
| `StreamingChatModel.stream(Prompt)` | Supported |
| `ChatClient.call()` | Supported |
| `ChatClient.stream()` | Supported |
| System/user/assistant text messages | Supported |
| Chat history supplied in `Prompt` | Supported |
| `ChatResponse` model and metadata | Supported |
| Usage metadata | Best effort |
| Reactor cancellation | Supported |
| Provider/model/effort selection | Supported |
| Tool callbacks | Not yet supported |
| Media and files | Not yet supported |
| Structured output | Not yet supported |
| Temperature, top-p, penalties, stop sequences | Rejected |
| Native provider session continuation | Intentionally unsupported |

Unsupported request options raise `SubAuthUnsupportedCapabilityException`.
SubAuth does not fabricate missing usage, finish-reason, or model data.

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
