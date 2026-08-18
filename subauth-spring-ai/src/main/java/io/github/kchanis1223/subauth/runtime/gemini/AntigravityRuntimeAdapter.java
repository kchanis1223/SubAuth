package io.github.kchanis1223.subauth.runtime.gemini;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kchanis1223.subauth.SubAuthEffort;
import io.github.kchanis1223.subauth.SubAuthException;
import io.github.kchanis1223.subauth.SubAuthProvider;
import io.github.kchanis1223.subauth.runtime.ConversationRenderer;
import io.github.kchanis1223.subauth.runtime.RuntimeAdapter;
import io.github.kchanis1223.subauth.runtime.RuntimeCapabilities;
import io.github.kchanis1223.subauth.runtime.RuntimeEvent;
import io.github.kchanis1223.subauth.runtime.RuntimeProbe;
import io.github.kchanis1223.subauth.runtime.RuntimeRequest;
import io.github.kchanis1223.subauth.runtime.RuntimeUsage;
import io.github.kchanis1223.subauth.runtime.process.ProcessSupport;
import reactor.core.publisher.Flux;

public final class AntigravityRuntimeAdapter implements RuntimeAdapter {
    public static final String POLICY_WARNING =
            "Google Antigravity terms restrict using third-party software to access the service. " +
            "Use this runtime only for developer-controlled evaluation and do not route production " +
            "traffic through it without Google authorization.";

    private static final Set<String> REMOVED_ENVIRONMENT = Set.of(
            "AGY_ADC_AUTH", "AGY_BUSINESS_PAYGO_TIER", "CLOUDSDK_AUTH_ACCESS_TOKEN",
            "CLOUDSDK_CORE_PROJECT", "GEMINI_API_KEY", "GOOGLE_API_KEY",
            "GOOGLE_APPLICATION_CREDENTIALS", "GOOGLE_CLOUD_LOCATION", "GOOGLE_CLOUD_PROJECT",
            "GOOGLE_CLOUD_QUOTA_PROJECT", "GOOGLE_GENAI_USE_VERTEXAI", "GOOGLE_OAUTH_ACCESS_TOKEN",
            "VERTEXAI_LOCATION", "VERTEXAI_PROJECT");

    private final ObjectMapper objectMapper;
    private final String command;
    private final Duration probeTimeout;
    private final Path settingsPath;

    public AntigravityRuntimeAdapter(ObjectMapper objectMapper, String command, Duration probeTimeout) {
        this(objectMapper, command, probeTimeout,
                Path.of(System.getProperty("user.home"), ".gemini", "antigravity-cli", "settings.json"));
    }

    AntigravityRuntimeAdapter(
            ObjectMapper objectMapper, String command, Duration probeTimeout, Path settingsPath) {
        this.objectMapper = objectMapper;
        this.command = command;
        this.probeTimeout = probeTimeout;
        this.settingsPath = settingsPath;
    }

    @Override public SubAuthProvider provider() { return SubAuthProvider.GEMINI; }

    @Override
    public RuntimeCapabilities capabilities() {
        return RuntimeCapabilities.textOnly(Set.of(
                SubAuthEffort.LOW,
                SubAuthEffort.MEDIUM,
                SubAuthEffort.HIGH));
    }

    @Override
    public RuntimeProbe probe() {
        try {
            if (creditFallbackEnabled()) {
                return new RuntimeProbe(provider(), true, false, null,
                        "Antigravity AI-credit fallback is enabled; disable useG1Credits before using SubAuth.",
                        List.of(), policyMetadata());
            }
            Map<String, String> environment = ProcessSupport.environmentWithout(REMOVED_ENVIRONMENT);
            String version = ProcessSupport.capture(
                    List.of(command, "--version"), environment, null, probeTimeout, false).stdout();
            ProcessSupport.Capture modelsResult = ProcessSupport.capture(
                    List.of(command, "models"), environment, null, probeTimeout, true);
            List<String> models = parseModels(modelsResult.stdout());
            boolean ready = modelsResult.exitCode() == 0 && !models.isEmpty();
            return new RuntimeProbe(provider(), true, ready, version,
                    ready ? "Antigravity subscription runtime is ready." : "Antigravity Google sign-in is required.",
                    models, policyMetadata());
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
        String model = selectModel(status.models(), request.model(), request.effort());

        Path workspace = ProcessSupport.temporaryDirectory("subauth-gemini-");
        List<String> arguments = new ArrayList<>(List.of(
                command, "-p", guardedPrompt(request), "--model", model,
                "--output-format", "stream-json", "--print-timeout", "5m",
                "--sandbox", "--disable-slash-commands"));
        if (request.effort() != null) arguments.addAll(List.of("--effort", request.effort().cliValue()));

        AtomicBoolean emittedText = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<String> conversationId = new AtomicReference<>();
        return ProcessSupport.streamJsonLines(
                        objectMapper, arguments, ProcessSupport.environmentWithout(REMOVED_ENVIRONMENT),
                        workspace, request.timeout())
                .<RuntimeEvent>handle((message, sink) -> {
                    String event = message.path("event").asText();
                    if ("init".equals(event)) {
                        String id = firstText(message.get("conversation_id"), message.path("init").get("conversation_id"));
                        if (id != null) conversationId.set(id);
                        sink.next(RuntimeEvent.started(metadata(model, conversationId.get())));
                    }
                    else if ("step_update".equals(event)) {
                        JsonNode step = message.path("step_update");
                        String id = textOrNull(step.get("conversation_id"));
                        if (id != null) conversationId.set(id);
                        String stepType = step.path("step_type").asText();
                        if ("tool".equals(stepType)) {
                            sink.error(new SubAuthException(
                                    "runtime_tool_use_blocked",
                                    "Antigravity attempted a tool call; SubAuth stopped the request."));
                        }
                        else if ("agent_response".equals(stepType)) {
                            String text = step.path("text_delta").asText();
                            if (!text.isEmpty()) {
                                emittedText.set(true);
                                sink.next(RuntimeEvent.delta(text, metadata(model, conversationId.get())));
                            }
                        }
                    }
                    else if ("result".equals(event)) {
                        JsonNode result = message.path("result");
                        String id = textOrNull(result.get("conversation_id"));
                        if (id != null) conversationId.set(id);
                        if (!"SUCCESS".equals(result.path("status").asText())) {
                            String detail = result.path("error").asText();
                            String errorMessage = detail.isBlank()
                                    ? "Antigravity returned an error"
                                    : "Antigravity returned an error: " + detail;
                            sink.error(new SubAuthException("antigravity_result_error", errorMessage));
                            return;
                        }
                        String response = result.path("response").asText();
                        if (!emittedText.get() && !response.isEmpty()) {
                            sink.next(RuntimeEvent.delta(response, metadata(model, conversationId.get())));
                        }
                        sink.next(RuntimeEvent.completed(
                                metadata(model, conversationId.get()),
                                usage(result.path("usage")),
                                finishReason(result)));
                        completed.set(true);
                    }
                })
                .concatWith(Flux.defer(() -> completed.get()
                        ? Flux.empty()
                        : Flux.error(new SubAuthException(
                                "incomplete_runtime_stream", "Antigravity ended without a result event"))))
                .doFinally(ignored -> ProcessSupport.deleteTemporaryDirectory(workspace));
    }

    static String selectModel(List<String> available, String requested, SubAuthEffort effort) {
        if (requested != null) {
            if (!available.contains(requested) || !requested.startsWith("gemini-")) {
                throw new SubAuthException(
                        "invalid_model", "The requested model is not available through Antigravity: " + requested);
            }
            if (effort != null && !requested.endsWith("-" + effort.cliValue())) {
                throw new SubAuthException(
                        "model_effort_conflict",
                        "The Antigravity model " + requested + " conflicts with effort=" + effort.cliValue());
            }
            return requested;
        }
        if (effort == null) {
            return available.stream()
                    .filter(model -> model.startsWith("gemini-"))
                    .findFirst()
                    .orElseThrow(() -> new SubAuthException(
                            "no_gemini_model", "Antigravity did not report an available Gemini model"));
        }
        String suffix = "-" + effort.cliValue();
        return available.stream()
                .filter(model -> model.startsWith("gemini-") && model.endsWith(suffix))
                .findFirst()
                .orElseThrow(() -> new SubAuthException(
                        "no_model_for_effort",
                        "Antigravity did not report an available Gemini model for effort=" + effort.cliValue()));
    }

    private String guardedPrompt(RuntimeRequest request) {
        StringBuilder prompt = new StringBuilder(
                "Respond with text only. Do not call tools, subagents, MCP servers, browse, " +
                "run commands, read files, or write files.");
        String system = ConversationRenderer.system(request);
        if (!system.isBlank()) prompt.append("\nAdditional system instruction:\n").append(system);
        return prompt.append("\n\nConversation:\n")
                .append(ConversationRenderer.conversation(request)).toString();
    }

    private boolean creditFallbackEnabled() {
        try {
            JsonNode settings = objectMapper.readTree(Files.readString(settingsPath));
            return settings.path("useG1Credits").asBoolean(false)
                    || settings.path("use_ai_credits").asBoolean(false);
        }
        catch (IOException ignored) {
            return false;
        }
    }

    private List<String> parseModels(String output) {
        Set<String> models = new LinkedHashSet<>();
        output.lines().map(String::trim).filter(line -> !line.isEmpty()).forEach(line -> {
            String model = line.split("\\s+", 2)[0];
            if (model.startsWith("gemini-")) models.add(model);
        });
        return List.copyOf(models);
    }

    @SuppressWarnings("unchecked")
    private RuntimeUsage usage(JsonNode node) {
        if (!node.isObject()) return RuntimeUsage.empty();
        Map<String, Object> nativeUsage = objectMapper.convertValue(node, Map.class);
        return new RuntimeUsage(
                integerOrNull(node.get("input_tokens")), integerOrNull(node.get("output_tokens")),
                integerOrNull(node.get("total_tokens")), longOrNull(node.get("cache_read_tokens")),
                null, nativeUsage);
    }

    private Map<String, Object> metadata(String model, String conversationId) {
        Map<String, Object> result = new HashMap<>(policyMetadata());
        result.put("model", model);
        if (conversationId != null) result.put("responseId", conversationId);
        return result;
    }

    private Map<String, Object> policyMetadata() {
        return Map.of("supportLevel", "terms-restricted", "policyWarning", POLICY_WARNING);
    }

    private static String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            String value = textOrNull(node);
            if (value != null) return value;
        }
        return null;
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static String finishReason(JsonNode result) {
        String value = textOrNull(result.get("finish_reason"));
        return value == null ? textOrNull(result.get("finishReason")) : value;
    }

    private static Integer integerOrNull(JsonNode node) { return node != null && node.isNumber() ? node.intValue() : null; }
    private static Long longOrNull(JsonNode node) { return node != null && node.isNumber() ? node.longValue() : null; }
}
