package io.github.kchanis1223.subauth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.kchanis1223.subauth.runtime.RuntimeEvent;
import io.github.kchanis1223.subauth.runtime.RuntimeRequest;
import io.github.kchanis1223.subauth.runtime.RuntimeUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

final class RuntimeResponseAccumulator {
    private final Map<Integer, GenerationState> generations = new TreeMap<>();
    private final Map<String, Object> responseMetadata = new LinkedHashMap<>();
    private RuntimeUsage usage = RuntimeUsage.empty();
    private boolean completed;

    void accept(RuntimeEvent event) {
        responseMetadata.putAll(event.responseMetadata());
        if (event.type() == RuntimeEvent.Type.TEXT_DELTA) {
            GenerationState generation = generation(event.generationIndex());
            generation.text.append(event.text());
            generation.messageMetadata.putAll(event.messageMetadata());
        }
        else if (event.type() == RuntimeEvent.Type.COMPLETED) {
            GenerationState generation = generation(event.generationIndex());
            generation.messageMetadata.putAll(event.messageMetadata());
            generation.finishReason = event.finishReason();
            usage = event.usage();
            completed = true;
        }
    }

    ChatResponse completedResponse(RuntimeRequest request, List<String> ignoredOptions) {
        if (!completed) {
            throw new SubAuthException(
                    "incomplete_runtime_stream", "The subscription runtime ended without completion");
        }
        return response(request, ignoredOptions);
    }

    static ChatResponse streamingResponse(
            RuntimeEvent event, RuntimeRequest request, List<String> ignoredOptions) {
        RuntimeResponseAccumulator accumulator = new RuntimeResponseAccumulator();
        accumulator.accept(event);
        return accumulator.response(request, ignoredOptions);
    }

    private ChatResponse response(RuntimeRequest request, List<String> ignoredOptions) {
        if (generations.isEmpty()) generation(0);
        List<Generation> results = generations.entrySet().stream()
                .map(entry -> generation(request, entry.getKey(), entry.getValue()))
                .toList();
        return new ChatResponse(new ArrayList<>(results), responseMetadata(request, ignoredOptions));
    }

    private Generation generation(RuntimeRequest request, int index, GenerationState state) {
        Map<String, Object> messageProperties = new LinkedHashMap<>(state.messageMetadata);
        messageProperties.putIfAbsent("provider", request.provider().name().toLowerCase());
        messageProperties.putIfAbsent("generationIndex", index);
        AssistantMessage message = AssistantMessage.builder()
                .content(state.text.toString())
                .properties(messageProperties)
                .build();

        var generationMetadata = ChatGenerationMetadata.builder()
                .metadata("provider", request.provider().name().toLowerCase())
                .metadata("generationIndex", index);
        if (state.finishReason != null) generationMetadata.finishReason(state.finishReason);
        return new Generation(message, generationMetadata.build());
    }

    private ChatResponseMetadata responseMetadata(
            RuntimeRequest request, List<String> ignoredOptions) {
        var metadata = ChatResponseMetadata.builder()
                .model(effectiveModel(request))
                .keyValue("provider", request.provider().name().toLowerCase())
                .keyValue("transport", "subscription-runtime");
        Object responseId = responseMetadata.get("responseId");
        if (responseId instanceof String id && !id.isBlank()) metadata.id(id);
        if (hasUsage()) {
            metadata.usage(springAiUsage());
        }
        if (usage.cacheReadTokens() != null) {
            metadata.keyValue("cacheReadTokens", usage.cacheReadTokens());
        }
        if (usage.cacheWriteTokens() != null) {
            metadata.keyValue("cacheWriteTokens", usage.cacheWriteTokens());
        }
        responseMetadata.forEach(metadata::keyValue);
        if (!ignoredOptions.isEmpty()) metadata.keyValue("ignoredOptions", ignoredOptions);
        return metadata.build();
    }

    private Usage springAiUsage() {
        try {
            return DefaultUsage.class
                    .getConstructor(
                            Integer.class, Integer.class, Integer.class,
                            Object.class, Long.class, Long.class)
                    .newInstance(
                            usage.inputTokens(), usage.outputTokens(), usage.totalTokens(),
                            usage.nativeUsage(), usage.cacheReadTokens(), usage.cacheWriteTokens());
        }
        catch (NoSuchMethodException ignored) {
            return new DefaultUsage(
                    usage.inputTokens(), usage.outputTokens(), usage.totalTokens(),
                    usage.nativeUsage());
        }
        catch (ReflectiveOperationException exception) {
            throw new SubAuthException(
                    "usage_mapping_failed",
                    "SubAuth could not map provider usage to the active Spring AI version",
                    exception);
        }
    }

    private String effectiveModel(RuntimeRequest request) {
        Object effective = responseMetadata.get("model");
        if (effective instanceof String value && !value.isBlank()) return value;
        return request.model() == null ? "auto" : request.model();
    }

    private boolean hasUsage() {
        return usage.inputTokens() != null || usage.outputTokens() != null || usage.totalTokens() != null;
    }

    private GenerationState generation(int index) {
        return generations.computeIfAbsent(index, ignored -> new GenerationState());
    }

    private static final class GenerationState {
        private final StringBuilder text = new StringBuilder();
        private final Map<String, Object> messageMetadata = new LinkedHashMap<>();
        private String finishReason;
    }
}
