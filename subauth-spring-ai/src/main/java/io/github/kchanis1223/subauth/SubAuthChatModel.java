package io.github.kchanis1223.subauth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.kchanis1223.subauth.runtime.RuntimeEvent;
import io.github.kchanis1223.subauth.runtime.RuntimeRegistry;
import io.github.kchanis1223.subauth.runtime.RuntimeRequest;
import io.github.kchanis1223.subauth.runtime.RuntimeUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

public final class SubAuthChatModel implements ChatModel {
    private final RuntimeRegistry runtimes;
    private final SubAuthChatOptions defaultOptions;
    private final Duration requestTimeout;

    public SubAuthChatModel(
            RuntimeRegistry runtimes,
            SubAuthChatOptions defaultOptions,
            Duration requestTimeout) {
        this.runtimes = runtimes;
        this.defaultOptions = defaultOptions;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        RuntimeRequest request = PromptRuntimeMapper.map(prompt, defaultOptions, requestTimeout);
        List<RuntimeEvent> events = runtimes.require(request.provider())
                .stream(request)
                .collectList()
                .block(requestTimeout.plusSeconds(5));
        if (events == null) {
            throw new SubAuthException("empty_runtime_response", "The subscription runtime returned no events");
        }

        StringBuilder text = new StringBuilder();
        Map<String, Object> metadata = Map.of();
        RuntimeUsage usage = RuntimeUsage.empty();
        boolean completed = false;
        for (RuntimeEvent event : events) {
            if (event.type() == RuntimeEvent.Type.TEXT_DELTA) text.append(event.text());
            if (!event.metadata().isEmpty()) metadata = event.metadata();
            if (event.type() == RuntimeEvent.Type.COMPLETED) {
                usage = event.usage();
                completed = true;
            }
        }
        if (!completed) {
            throw new SubAuthException(
                    "incomplete_runtime_stream", "The subscription runtime ended without completion");
        }
        return response(text.toString(), request, metadata, usage, "STOP");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            RuntimeRequest request = PromptRuntimeMapper.map(prompt, defaultOptions, requestTimeout);
            return runtimes.require(request.provider()).stream(request)
                    .<ChatResponse>handle((event, sink) -> {
                        if (event.type() == RuntimeEvent.Type.TEXT_DELTA) {
                            sink.next(response(event.text(), request, event.metadata(), event.usage(), null));
                        }
                        else if (event.type() == RuntimeEvent.Type.COMPLETED) {
                            sink.next(response("", request, event.metadata(), event.usage(), "STOP"));
                        }
                    });
        });
    }

    @Override
    public ChatOptions getOptions() {
        return defaultOptions;
    }

    private ChatResponse response(
            String text,
            RuntimeRequest request,
            Map<String, Object> runtimeMetadata,
            RuntimeUsage usage,
            String finishReason) {
        var generationMetadataBuilder = ChatGenerationMetadata.builder()
                .metadata("provider", request.provider().name().toLowerCase());
        if (finishReason != null) generationMetadataBuilder.finishReason(finishReason);
        runtimeMetadata.forEach(generationMetadataBuilder::metadata);

        Generation generation = new Generation(
                new AssistantMessage(text),
                generationMetadataBuilder.build());

        var responseMetadata = ChatResponseMetadata.builder()
                .model(effectiveModel(request, runtimeMetadata))
                .keyValue("provider", request.provider().name().toLowerCase())
                .keyValue("transport", "subscription-runtime");
        Object responseId = runtimeMetadata.get("responseId");
        if (responseId instanceof String id) responseMetadata.id(id);
        if (usage.inputTokens() != null || usage.outputTokens() != null || usage.totalTokens() != null) {
            responseMetadata.usage(new DefaultUsage(
                    usage.inputTokens(), usage.outputTokens(), usage.totalTokens(),
                    usage.nativeUsage(), usage.cacheReadTokens(), usage.cacheWriteTokens()));
        }
        runtimeMetadata.forEach(responseMetadata::keyValue);
        return new ChatResponse(new ArrayList<>(List.of(generation)), responseMetadata.build());
    }

    private String effectiveModel(RuntimeRequest request, Map<String, Object> metadata) {
        Object effective = metadata.get("model");
        if (effective instanceof String value && !value.isBlank()) return value;
        return request.model() == null ? "auto" : request.model();
    }
}
