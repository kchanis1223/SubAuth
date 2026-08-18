# SubAuth

SubAuth is a Spring AI `ChatModel` backed by the developer's local AI
subscription runtimes. A Spring Boot application keeps using `ChatClient`,
`Prompt`, `ChatResponse`, Reactor streaming, advisors, and chat memory; SubAuth
replaces only the model transport used during development and controlled demos.

```text
Spring AI ChatClient
        -> SubAuthChatModel
            -> Codex App Server (ChatGPT subscription)
            -> Claude Code (Claude subscription, experimental)
            -> Antigravity (Google subscription, terms-restricted)
```

SubAuth is intentionally Spring AI-specific. It is not an HTTP proxy, an API
compatibility server, a Python SDK, or an end-user authentication system.

## Requirements

- macOS
- Java 21+
- Spring Boot 4.1+
- Spring AI 2.0+
- At least one supported provider CLI logged in with a subscription account

## Build

```bash
mvn test
mvn install
```

The build produces:

- `subauth-spring-ai`: `SubAuthChatModel`, options, and runtime adapters
- `subauth-spring-boot-autoconfigure`: Spring Boot auto-configuration
- `subauth-spring-boot-starter`: the dependency applications normally add

## Spring Boot usage

After installing the current snapshot locally, add the starter to the main
service:

```xml
<dependency>
    <groupId>io.github.kchanis1223</groupId>
    <artifactId>subauth-spring-boot-starter</artifactId>
    <version>0.2.0-SNAPSHOT</version>
</dependency>
```

Select SubAuth as the Spring AI chat model:

```yaml
spring:
  ai:
    model:
      chat: subauth
    subauth:
      provider: openai
      model: auto
      effort: medium
      request-timeout: 5m
```

Application code remains normal Spring AI code:

```java
@Service
class AiService {
    private final ChatClient chatClient;

    AiService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    String chat(String message) {
        return chatClient.prompt().user(message).call().content();
    }

    Flux<String> stream(String message) {
        return chatClient.prompt().user(message).stream().content();
    }
}
```

Spring AI chat memory remains responsible for conversation state. Each SubAuth
request starts a fresh provider runtime context and receives the complete
message history in its `Prompt`.

### Request-specific provider options

`SubAuthChatOptions` can override the configured provider, model, and effort:

```java
Prompt prompt = new Prompt(
    "Reply exactly: SUBAUTH_OK",
    SubAuthChatOptions.builder()
        .provider(SubAuthProvider.CLAUDE)
        .model("sonnet")
        .effort(SubAuthEffort.HIGH)
        .build()
);

ChatResponse response = chatModel.call(prompt);
```

Generic generation settings that the subscription runtimes cannot guarantee
are rejected with `SubAuthUnsupportedCapabilityException`; they are never
silently ignored.

## Provider setup

### OpenAI

Install the current Codex CLI and sign in with ChatGPT. SubAuth talks to the
official persistent `codex app-server --stdio` runtime, verifies that the
account type is `chatgpt`, creates an ephemeral read-only thread, and disables
approval-driven actions.

```yaml
spring.ai.subauth.provider: openai
spring.ai.subauth.commands.codex: codex
```

### Claude

Install Claude Code and complete its normal Claude.ai login. SubAuth strips API
key, Bedrock, Vertex, and Foundry environment variables, disables tools and MCP,
uses safe mode, and disables session persistence. An existing setup token in
`CLAUDE_CODE_OAUTH_TOKEN` or the legacy SubAuth macOS Keychain item is also
recognized.

```yaml
spring.ai.subauth.provider: claude
spring.ai.subauth.commands.claude: claude
```

Claude subscription routing is experimental and for development or limited
previews only. Anthropic directs product developers to supported API
authentication; migrate to the Anthropic API before formal release.

### Gemini

Install Antigravity CLI and run `agy` once to complete Google sign-in. SubAuth
strips Gemini API, ADC, Vertex, and Google Cloud credentials, refuses to run
when purchased AI-credit fallback is enabled, uses a temporary sandbox, and
stops the request if Antigravity attempts a tool call.

```yaml
spring.ai.subauth.provider: gemini
spring.ai.subauth.commands.gemini: agy
```

Google's Antigravity terms restrict access through third-party software. This
adapter is limited to developer-controlled evaluation and must not carry
production traffic without Google authorization.

## Current compatibility

Implemented:

- Spring AI `ChatModel.call(Prompt)`
- Spring AI `ChatModel.stream(Prompt)` returning `Flux<ChatResponse>`
- system, user, and assistant text messages
- stateless full-history prompts
- provider/model/effort selection
- usage and runtime metadata when observable
- Reactor cancellation propagated to provider runtimes
- Spring Boot auto-configuration and optional health indicator

Not implemented yet:

- Spring AI tool callbacks
- multimodal messages and files
- structured-output guarantees
- portable temperature/top-p/penalty/max-token controls
- API-key production transport

See [architecture](docs/architecture.md),
[compatibility](docs/compatibility.md), and
[runtime policies](docs/runtime-policies.md).
