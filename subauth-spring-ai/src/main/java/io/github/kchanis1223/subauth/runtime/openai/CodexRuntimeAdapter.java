package io.github.kchanis1223.subauth.runtime.openai;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kchanis1223.subauth.SubAuthEffort;
import io.github.kchanis1223.subauth.SubAuthException;
import io.github.kchanis1223.subauth.SubAuthProvider;
import io.github.kchanis1223.subauth.SubAuthUnsupportedCapabilityException;
import io.github.kchanis1223.subauth.runtime.ConversationRenderer;
import io.github.kchanis1223.subauth.runtime.RuntimeAdapter;
import io.github.kchanis1223.subauth.runtime.RuntimeCapabilities;
import io.github.kchanis1223.subauth.runtime.RuntimeContent;
import io.github.kchanis1223.subauth.runtime.RuntimeEvent;
import io.github.kchanis1223.subauth.runtime.RuntimeOption;
import io.github.kchanis1223.subauth.runtime.RuntimeProbe;
import io.github.kchanis1223.subauth.runtime.RuntimeRequest;
import io.github.kchanis1223.subauth.runtime.RuntimeTool;
import io.github.kchanis1223.subauth.runtime.RuntimeUsage;
import reactor.core.publisher.Flux;

public final class CodexRuntimeAdapter implements RuntimeAdapter {
    private static final int MAX_TOOL_CALLS = 8;

    private final ObjectMapper objectMapper;
    private final CodexAppServerTransport client;

    public CodexRuntimeAdapter(ObjectMapper objectMapper, String command, Duration requestTimeout) {
        this(objectMapper, new CodexAppServerClient(objectMapper, command, requestTimeout));
    }

    CodexRuntimeAdapter(ObjectMapper objectMapper, CodexAppServerTransport client) {
        this.objectMapper = objectMapper;
        this.client = client;
    }

    @Override public SubAuthProvider provider() { return SubAuthProvider.OPENAI; }

    @Override
    public RuntimeCapabilities capabilities() {
        return new RuntimeCapabilities(
                true, true, true, true, false, false,
                Set.of(SubAuthEffort.values()),
                Set.of(RuntimeOption.MODEL, RuntimeOption.EFFORT, RuntimeOption.TOOL_CALLBACKS));
    }

    @Override
    public RuntimeProbe probe() {
        try {
            JsonNode accountResult = client.request("account/read", Map.of("refreshToken", false));
            JsonNode account = accountResult.get("account");
            JsonNode modelResult = client.request(
                    "model/list", Map.of("includeHidden", false, "limit", 100));
            ModelCatalog catalog = modelCatalog(modelResult.path("data"));
            boolean ready = account != null && account.isObject()
                    && "chatgpt".equals(account.path("type").asText());
            String plan = account == null ? null : textOrNull(account.get("planType"));
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (plan != null) metadata.put("planType", plan);
            if (catalog.defaultModel() != null) metadata.put("defaultModel", catalog.defaultModel());
            metadata.put("modelInputModalities", catalog.inputModalities());
            return new RuntimeProbe(provider(), true, ready, null,
                    ready ? "ChatGPT subscription is ready through Codex App Server."
                            : "A ChatGPT subscription login is required.",
                    catalog.models(), metadata);
        }
        catch (Exception error) {
            return new RuntimeProbe(provider(), false, false, null, error.getMessage(), List.of(), Map.of());
        }
    }

    @Override
    public Flux<RuntimeEvent> stream(RuntimeRequest request) {
        capabilities().validate(request);
        return Flux.create(sink -> {
            AtomicReference<String> threadIdRef = new AtomicReference<>();
            AtomicReference<String> turnIdRef = new AtomicReference<>();
            Thread worker = Thread.ofVirtual().name("subauth-codex-request").start(() -> {
                RuntimeProbe status = probe();
                if (!status.subscriptionReady()) {
                    sink.error(new SubAuthException("subscription_not_ready", status.detail()));
                    return;
                }
                Map<String, Object> threadParams = new HashMap<>();
                threadParams.put("approvalPolicy", "never");
                threadParams.put("ephemeral", true);
                threadParams.put("sandbox", "read-only");
                if (request.model() != null) threadParams.put("model", request.model());
                if (request.effort() != null) threadParams.put("reasoningEffort", request.effort().cliValue());
                String system = ConversationRenderer.system(request);
                if (!system.isBlank()) threadParams.put("baseInstructions", system);
                if (!request.tools().isEmpty()) {
                    threadParams.put("dynamicTools", dynamicTools(request.tools()));
                }

                try (CodexTurnInput turnInput = validatedTurnInput(request, status)) {
                    JsonNode threadResult = client.request("thread/start", threadParams);
                    String threadId = threadResult.path("thread").path("id").asText();
                    if (threadId.isBlank()) throw new SubAuthException(
                            "runtime_protocol_error", "Codex App Server did not return a thread id");
                    threadIdRef.set(threadId);
                    String model = textOrNull(threadResult.get("model"));
                    Map<String, Object> metadata = metadata(model, threadId, null, turnInput.mediaCount());
                    sink.next(RuntimeEvent.started(metadata));

                    try (CodexAppServerTransport.Subscription subscription = client.subscribe()) {
                        JsonNode turnResult = client.request("turn/start", Map.of(
                                "threadId", threadId,
                                "input", turnInput.values()));
                        String turnId = turnResult.path("turn").path("id").asText();
                        if (turnId.isBlank()) throw new SubAuthException(
                                "runtime_protocol_error", "Codex App Server did not return a turn id");
                        turnIdRef.set(turnId);
                        Instant deadline = Instant.now().plus(request.timeout());
                        Map<String, RuntimeTool> tools = toolsByName(request.tools());
                        AtomicInteger toolCallCount = new AtomicInteger();
                        while (!sink.isCancelled() && Instant.now().isBefore(deadline)) {
                            JsonNode notification = subscription.poll(Duration.ofMillis(500));
                            if (notification == null) continue;
                            JsonNode params = notification.path("params");
                            if (!threadId.equals(params.path("threadId").asText())) continue;
                            String eventTurnId = params.path("turnId").asText();
                            if (!eventTurnId.isBlank() && !turnId.equals(eventTurnId)) continue;
                            String method = notification.path("method").asText();
                            if ("item/agentMessage/delta".equals(method)) {
                                String delta = params.path("delta").asText();
                                if (!delta.isEmpty()) sink.next(RuntimeEvent.delta(
                                        delta, metadata(model, threadId, turnId, turnInput.mediaCount())));
                            }
                            else if ("item/tool/call".equals(method)) {
                                int callNumber = toolCallCount.incrementAndGet();
                                if (callNumber > MAX_TOOL_CALLS) {
                                    client.respond(notification.get("id"), toolResponse(
                                            false, "SubAuth tool-call limit exceeded"));
                                    throw new SubAuthException(
                                            "tool_call_limit_exceeded",
                                            "Codex requested more than " + MAX_TOOL_CALLS + " tool calls");
                                }
                                client.respond(
                                        notification.get("id"),
                                        executeToolCall(params, tools, deadline));
                            }
                            else if ("error".equals(method) && !params.path("willRetry").asBoolean(false)) {
                                throw new SubAuthException("codex_runtime_error", "Codex App Server returned an error");
                            }
                            else if ("turn/completed".equals(method)) {
                                JsonNode turn = params.path("turn");
                                if (!turnId.equals(turn.path("id").asText())) continue;
                                if (!"completed".equals(turn.path("status").asText())) {
                                    throw new SubAuthException("codex_runtime_error", "Codex turn did not complete successfully");
                                }
                                sink.next(RuntimeEvent.completed(
                                        metadata(model, threadId, turnId, turnInput.mediaCount()),
                                        usage(turn.path("usage")),
                                        finishReason(turn)));
                                sink.complete();
                                return;
                            }
                        }
                        if (!sink.isCancelled()) {
                            throw new SubAuthException("runtime_timeout", "Codex request timed out");
                        }
                    }
                }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    if (!sink.isCancelled()) sink.error(new SubAuthException(
                            "runtime_interrupted", "Codex request was interrupted", error));
                }
                catch (Exception error) {
                    if (!sink.isCancelled()) sink.error(error);
                }
            });

            sink.onCancel(() -> {
                String threadId = threadIdRef.get();
                String turnId = turnIdRef.get();
                if (threadId != null && turnId != null) {
                    try {
                        client.request("turn/interrupt", Map.of("threadId", threadId, "turnId", turnId));
                    }
                    catch (Exception ignored) {
                        // Cancellation still interrupts the waiting worker.
                    }
                }
                worker.interrupt();
            });
        });
    }

    private List<Map<String, Object>> dynamicTools(List<RuntimeTool> tools) {
        return tools.stream().map(tool -> {
            JsonNode schema;
            try {
                schema = objectMapper.readTree(tool.inputSchema());
            }
            catch (Exception error) {
                throw new SubAuthException(
                        "invalid_tool_schema",
                        "Spring AI tool has invalid JSON Schema: " + tool.name(),
                        error);
            }
            if (schema == null || !schema.isObject()) {
                throw new SubAuthException(
                        "invalid_tool_schema",
                        "Spring AI tool input schema must be a JSON object: " + tool.name());
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", "function");
            value.put("name", tool.name());
            value.put("description", tool.description());
            value.put("inputSchema", schema);
            return value;
        }).toList();
    }

    private Map<String, RuntimeTool> toolsByName(List<RuntimeTool> tools) {
        Map<String, RuntimeTool> values = new LinkedHashMap<>();
        tools.forEach(tool -> values.put(tool.name(), tool));
        return Map.copyOf(values);
    }

    private Map<String, Object> executeToolCall(
            JsonNode params,
            Map<String, RuntimeTool> tools,
            Instant deadline) throws InterruptedException {
        String toolName = params.path("tool").asText();
        RuntimeTool tool = tools.get(toolName);
        if (tool == null) {
            return toolResponse(false, "Unknown tool: " + toolName);
        }
        JsonNode argumentsNode = params.get("arguments");
        String arguments;
        try {
            arguments = argumentsNode == null
                    ? "{}"
                    : objectMapper.writeValueAsString(argumentsNode);
        }
        catch (Exception error) {
            return toolResponse(false, "Tool arguments are not valid JSON");
        }

        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            return toolResponse(false, "SubAuth request timed out before tool execution");
        }
        FutureTask<String> execution = new FutureTask<>(() -> tool.execute(arguments));
        Thread executionThread = Thread.ofVirtual()
                .name("subauth-tool-" + tool.name())
                .start(execution);
        try {
            String output = execution.get(
                    Math.max(1L, remaining.toMillis()), TimeUnit.MILLISECONDS);
            return toolResponse(true, output == null ? "" : output);
        }
        catch (TimeoutException error) {
            executionThread.interrupt();
            return toolResponse(false, "Tool execution timed out: " + tool.name());
        }
        catch (ExecutionException error) {
            Throwable cause = error.getCause();
            String detail = cause == null || cause.getMessage() == null
                    ? cause == null ? "unknown error" : cause.getClass().getSimpleName()
                    : cause.getMessage();
            return toolResponse(false, "Tool execution failed: " + detail);
        }
    }

    private static Map<String, Object> toolResponse(boolean success, String text) {
        return Map.of(
                "success", success,
                "contentItems", List.of(Map.of(
                        "type", "inputText",
                        "text", text)));
    }

    @SuppressWarnings("unchecked")
    private RuntimeUsage usage(JsonNode node) {
        if (!node.isObject()) return RuntimeUsage.empty();
        Map<String, Object> nativeUsage = objectMapper.convertValue(node, Map.class);
        Integer input = firstInteger(node, "input_tokens", "inputTokens");
        Integer output = firstInteger(node, "output_tokens", "outputTokens");
        Integer total = firstInteger(node, "total_tokens", "totalTokens");
        return new RuntimeUsage(input, output, total, null, null, nativeUsage);
    }

    private Map<String, Object> metadata(
            String model, String threadId, String turnId, int mediaCount) {
        Map<String, Object> metadata = new HashMap<>();
        if (model != null) metadata.put("model", model);
        metadata.put("threadId", threadId);
        if (turnId != null) {
            metadata.put("turnId", turnId);
            metadata.put("responseId", turnId);
        }
        if (mediaCount > 0) metadata.put("mediaCount", mediaCount);
        return metadata;
    }

    private void validateImageModel(RuntimeRequest request, RuntimeProbe status) {
        boolean hasImages = request.messages().stream()
                .flatMap(message -> message.contents().stream())
                .anyMatch(RuntimeContent.Media.class::isInstance);
        if (!hasImages) return;

        String selectedModel = request.model();
        if (selectedModel == null) {
            Object defaultModel = status.metadata().get("defaultModel");
            if (defaultModel instanceof String value) selectedModel = value;
        }
        if (selectedModel == null) return;

        Object rawCatalog = status.metadata().get("modelInputModalities");
        if (!(rawCatalog instanceof Map<?, ?> catalog)) return;
        Object rawModalities = catalog.get(selectedModel);
        if (rawModalities instanceof Collection<?> modalities && !modalities.contains("image")) {
            throw new SubAuthUnsupportedCapabilityException(
                    "The selected Codex model does not support image input: " + selectedModel);
        }
    }

    private CodexTurnInput validatedTurnInput(RuntimeRequest request, RuntimeProbe status) {
        validateImageModel(request, status);
        return CodexTurnInput.create(request);
    }

    private ModelCatalog modelCatalog(JsonNode data) {
        List<String> models = new ArrayList<>();
        Map<String, List<String>> inputModalities = new LinkedHashMap<>();
        AtomicReference<String> defaultModel = new AtomicReference<>();
        data.forEach(model -> {
            String id = model.path("id").asText();
            if (id.isBlank()) return;
            models.add(id);
            List<String> modalities = new ArrayList<>();
            JsonNode advertised = model.get("inputModalities");
            if (advertised == null || !advertised.isArray()) {
                modalities.addAll(List.of("text", "image"));
            }
            else {
                advertised.forEach(value -> {
                    if (value.isTextual()) modalities.add(value.asText());
                });
            }
            inputModalities.put(id, List.copyOf(modalities));
            if (model.path("isDefault").asBoolean(false)) defaultModel.set(id);
        });
        return new ModelCatalog(List.copyOf(models), Map.copyOf(inputModalities), defaultModel.get());
    }

    private static Integer firstInteger(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isNumber()) return value.intValue();
        }
        return null;
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static String finishReason(JsonNode turn) {
        String value = textOrNull(turn.get("finishReason"));
        return value == null ? textOrNull(turn.get("finish_reason")) : value;
    }

    private record ModelCatalog(
            List<String> models,
            Map<String, List<String>> inputModalities,
            String defaultModel) {}

    @Override
    public void close() {
        client.close();
    }
}
