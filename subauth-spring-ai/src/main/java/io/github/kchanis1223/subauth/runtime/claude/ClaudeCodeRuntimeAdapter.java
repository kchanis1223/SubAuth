package io.github.kchanis1223.subauth.runtime.claude;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kchanis1223.subauth.SubAuthException;
import io.github.kchanis1223.subauth.SubAuthEffort;
import io.github.kchanis1223.subauth.SubAuthProvider;
import io.github.kchanis1223.subauth.runtime.ConversationRenderer;
import io.github.kchanis1223.subauth.runtime.RuntimeAdapter;
import io.github.kchanis1223.subauth.runtime.RuntimeCapabilities;
import io.github.kchanis1223.subauth.runtime.RuntimeEvent;
import io.github.kchanis1223.subauth.runtime.RuntimeProbe;
import io.github.kchanis1223.subauth.runtime.RuntimeRequest;
import io.github.kchanis1223.subauth.runtime.RuntimeUsage;
import io.github.kchanis1223.subauth.runtime.process.MacOsKeychain;
import io.github.kchanis1223.subauth.runtime.process.ProcessSupport;
import reactor.core.publisher.Flux;

public final class ClaudeCodeRuntimeAdapter implements RuntimeAdapter {
    public static final String POLICY_WARNING =
            "Anthropic does not permit third-party products to route user requests through " +
            "consumer Claude subscription credentials. Use this transport only for development " +
            "and limited previews, and migrate to the Anthropic API before formal release.";

    private static final Set<String> REMOVED_ENVIRONMENT = Set.of(
            "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL",
            "ANTHROPIC_CUSTOM_HEADERS", "ANTHROPIC_PROFILE", "ANTHROPIC_FEDERATION_RULE_ID",
            "ANTHROPIC_IDENTITY_TOKEN_FILE", "ANTHROPIC_ORGANIZATION_ID",
            "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_FOUNDRY", "CLAUDE_CODE_USE_VERTEX",
            "GOOGLE_APPLICATION_CREDENTIALS");

    private final ObjectMapper objectMapper;
    private final String command;
    private final Duration probeTimeout;
    private final MacOsKeychain keychain;

    public ClaudeCodeRuntimeAdapter(ObjectMapper objectMapper, String command, Duration probeTimeout) {
        this(objectMapper, command, probeTimeout, new MacOsKeychain());
    }

    ClaudeCodeRuntimeAdapter(
            ObjectMapper objectMapper, String command, Duration probeTimeout, MacOsKeychain keychain) {
        this.objectMapper = objectMapper;
        this.command = command;
        this.probeTimeout = probeTimeout;
        this.keychain = keychain;
    }

    @Override public SubAuthProvider provider() { return SubAuthProvider.CLAUDE; }

    @Override
    public RuntimeCapabilities capabilities() {
        return RuntimeCapabilities.textOnly(Set.of(
                SubAuthEffort.LOW,
                SubAuthEffort.MEDIUM,
                SubAuthEffort.HIGH,
                SubAuthEffort.XHIGH,
                SubAuthEffort.MAX));
    }

    @Override
    public RuntimeProbe probe() {
        try {
            Map<String, String> environment = subscriptionEnvironment();
            String version = ProcessSupport.capture(
                    List.of(command, "--version"), environment, null, probeTimeout, false).stdout();
            ProcessSupport.Capture auth = ProcessSupport.capture(
                    List.of(command, "auth", "status", "--json"), environment, null, probeTimeout, true);
            JsonNode value = objectMapper.readTree(auth.stdout().isBlank() ? "{}" : auth.stdout());
            String method = value.path("authMethod").asText("unknown");
            boolean loggedIn = value.path("loggedIn").asBoolean(false);
            boolean subscription = loggedIn && ("claude.ai".equals(method)
                    || "claudeai".equals(method) || "oauth_token".equals(method));
            return new RuntimeProbe(provider(), true, subscription, version,
                    subscription ? "Claude subscription runtime is ready." : "Claude subscription login is required.",
                    List.of(), policyMetadata());
        }
        catch (Exception error) {
            return new RuntimeProbe(provider(), false, false, null, error.getMessage(), List.of(), policyMetadata());
        }
    }

    @Override
    public Flux<RuntimeEvent> stream(RuntimeRequest request) {
        capabilities().validate(request);
        RuntimeProbe status = probe();
        if (!status.subscriptionReady()) {
            return Flux.error(new SubAuthException("subscription_not_ready", status.detail()));
        }
        Path workspace = ProcessSupport.temporaryDirectory("subauth-claude-");
        List<String> arguments = new ArrayList<>(List.of(
                command, "-p", ConversationRenderer.conversation(request),
                "--output-format", "stream-json", "--verbose", "--include-partial-messages",
                "--no-session-persistence", "--safe-mode", "--disable-slash-commands",
                "--strict-mcp-config", "--mcp-config", "{\"mcpServers\":{}}",
                "--permission-mode", "dontAsk", "--tools", ""));
        if (request.model() != null) arguments.addAll(List.of("--model", request.model()));
        if (request.effort() != null) arguments.addAll(List.of("--effort", request.effort().cliValue()));
        String system = ConversationRenderer.system(request);
        if (!system.isBlank()) arguments.addAll(List.of("--system-prompt", system));

        AtomicBoolean emittedText = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<String> model = new AtomicReference<>(request.model());
        AtomicReference<String> sessionId = new AtomicReference<>();
        return ProcessSupport.streamJsonLines(
                        objectMapper, arguments, subscriptionEnvironment(), workspace, request.timeout())
                .<RuntimeEvent>handle((message, sink) -> {
                    String type = message.path("type").asText();
                    if ("system".equals(type) && "init".equals(message.path("subtype").asText())) {
                        sessionId.set(textOrNull(message.get("session_id")));
                        String selected = textOrNull(message.get("model"));
                        if (selected != null) model.set(selected);
                        sink.next(RuntimeEvent.started(metadata(model.get(), sessionId.get())));
                    }
                    else if ("stream_event".equals(type)) {
                        JsonNode event = message.path("event");
                        JsonNode delta = event.path("delta");
                        if ("content_block_delta".equals(event.path("type").asText())
                                && "text_delta".equals(delta.path("type").asText())) {
                            String text = delta.path("text").asText();
                            if (!text.isEmpty()) {
                                emittedText.set(true);
                                sink.next(RuntimeEvent.delta(text, metadata(model.get(), sessionId.get())));
                            }
                        }
                    }
                    else if ("result".equals(type)) {
                        if (message.path("is_error").asBoolean(false)
                                || !"success".equals(message.path("subtype").asText())) {
                            sink.error(new SubAuthException("claude_runtime_error", "Claude Code returned an error"));
                            return;
                        }
                        String result = message.path("result").asText();
                        if (!emittedText.get() && !result.isEmpty()) {
                            sink.next(RuntimeEvent.delta(result, metadata(model.get(), sessionId.get())));
                        }
                        sink.next(RuntimeEvent.completed(
                                metadata(model.get(), sessionId.get()),
                                usage(message.path("usage")),
                                finishReason(message)));
                        completed.set(true);
                    }
                })
                .concatWith(Flux.defer(() -> completed.get()
                        ? Flux.empty()
                        : Flux.error(new SubAuthException(
                                "incomplete_runtime_stream", "Claude Code ended without a result event"))))
                .doFinally(ignored -> ProcessSupport.deleteTemporaryDirectory(workspace));
    }

    private Map<String, String> subscriptionEnvironment() {
        Map<String, String> environment = ProcessSupport.environmentWithout(REMOVED_ENVIRONMENT);
        if (!environment.containsKey("CLAUDE_CODE_OAUTH_TOKEN")) {
            String token = keychain.read("claude/setup-token");
            if (token != null) environment.put("CLAUDE_CODE_OAUTH_TOKEN", token);
        }
        environment.put("CLAUDE_CODE_SAFE_MODE", "1");
        return environment;
    }

    @SuppressWarnings("unchecked")
    private RuntimeUsage usage(JsonNode node) {
        if (!node.isObject()) return RuntimeUsage.empty();
        Map<String, Object> nativeUsage = objectMapper.convertValue(node, Map.class);
        Integer input = integerOrNull(node.get("input_tokens"));
        Integer output = integerOrNull(node.get("output_tokens"));
        return new RuntimeUsage(input, output, sum(input, output),
                longOrNull(node.get("cache_read_input_tokens")),
                longOrNull(node.get("cache_creation_input_tokens")), nativeUsage);
    }

    private Map<String, Object> metadata(String model, String sessionId) {
        Map<String, Object> result = new HashMap<>(policyMetadata());
        if (model != null) result.put("model", model);
        if (sessionId != null) result.put("responseId", sessionId);
        return result;
    }

    private Map<String, Object> policyMetadata() {
        return Map.of("supportLevel", "experimental", "policyWarning", POLICY_WARNING);
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static String finishReason(JsonNode result) {
        String value = textOrNull(result.get("stop_reason"));
        return value == null ? textOrNull(result.get("stopReason")) : value;
    }

    private static Integer integerOrNull(JsonNode node) { return node != null && node.isNumber() ? node.intValue() : null; }
    private static Long longOrNull(JsonNode node) { return node != null && node.isNumber() ? node.longValue() : null; }
    private static Integer sum(Integer left, Integer right) { return left == null || right == null ? null : left + right; }
}
