package io.github.kchanis1223.subauth;

import java.time.Duration;
import java.util.List;

import io.github.kchanis1223.subauth.runtime.RuntimeEvent;
import io.github.kchanis1223.subauth.runtime.RuntimeRegistry;
import io.github.kchanis1223.subauth.runtime.RuntimeRequest;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
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
        RuntimeRequest request = PromptRuntimeMapper.map(
                prompt, defaultOptions, requestTimeout,
                provider -> runtimes.require(provider).capabilities());
        List<RuntimeEvent> events = runtimes.require(request.provider())
                .stream(request)
                .collectList()
                .block(requestTimeout.plusSeconds(5));
        if (events == null) {
            throw new SubAuthException("empty_runtime_response", "The subscription runtime returned no events");
        }

        RuntimeResponseAccumulator accumulator = new RuntimeResponseAccumulator();
        events.forEach(accumulator::accept);
        return accumulator.completedResponse(request);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            RuntimeRequest request = PromptRuntimeMapper.map(
                    prompt, defaultOptions, requestTimeout,
                    provider -> runtimes.require(provider).capabilities());
            return runtimes.require(request.provider()).stream(request)
                    .<ChatResponse>handle((event, sink) -> {
                        if (event.type() == RuntimeEvent.Type.TEXT_DELTA) {
                            sink.next(RuntimeResponseAccumulator.streamingResponse(event, request));
                        }
                        else if (event.type() == RuntimeEvent.Type.COMPLETED) {
                            sink.next(RuntimeResponseAccumulator.streamingResponse(event, request));
                        }
                    });
        });
    }

    @Override
    public ChatOptions getOptions() {
        return defaultOptions;
    }

}
