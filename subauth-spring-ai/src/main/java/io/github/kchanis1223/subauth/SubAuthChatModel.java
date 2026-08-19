package io.github.kchanis1223.subauth;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import io.github.kchanis1223.subauth.runtime.RuntimeEvent;
import io.github.kchanis1223.subauth.runtime.RuntimeRegistry;
import io.github.kchanis1223.subauth.runtime.RuntimeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

public final class SubAuthChatModel implements ChatModel {
    private static final Logger LOGGER = LoggerFactory.getLogger(SubAuthChatModel.class);

    private final RuntimeRegistry runtimes;
    private final SubAuthChatOptions defaultOptions;
    private final Duration requestTimeout;
    private final SubAuthUnsupportedOptionsPolicy unsupportedOptionsPolicy;

    public SubAuthChatModel(
            RuntimeRegistry runtimes,
            SubAuthChatOptions defaultOptions,
            Duration requestTimeout) {
        this(runtimes, defaultOptions, requestTimeout, SubAuthUnsupportedOptionsPolicy.IGNORE);
    }

    public SubAuthChatModel(
            RuntimeRegistry runtimes,
            SubAuthChatOptions defaultOptions,
            Duration requestTimeout,
            SubAuthUnsupportedOptionsPolicy unsupportedOptionsPolicy) {
        this.runtimes = runtimes;
        this.defaultOptions = defaultOptions;
        this.requestTimeout = requestTimeout;
        this.unsupportedOptionsPolicy = Objects.requireNonNull(
                unsupportedOptionsPolicy, "unsupportedOptionsPolicy");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        PromptRuntimeMapper.MappingResult mapping = PromptRuntimeMapper.map(
                prompt, defaultOptions, requestTimeout, unsupportedOptionsPolicy,
                provider -> runtimes.require(provider).capabilities());
        RuntimeRequest request = mapping.request();
        warnIfNeeded(request, mapping.ignoredOptions());
        List<RuntimeEvent> events = runtimes.require(request.provider())
                .stream(request)
                .collectList()
                .block(requestTimeout.plusSeconds(5));
        if (events == null) {
            throw new SubAuthException("empty_runtime_response", "The subscription runtime returned no events");
        }

        RuntimeResponseAccumulator accumulator = new RuntimeResponseAccumulator();
        events.forEach(accumulator::accept);
        return accumulator.completedResponse(request, mapping.ignoredOptions());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            PromptRuntimeMapper.MappingResult mapping = PromptRuntimeMapper.map(
                    prompt, defaultOptions, requestTimeout, unsupportedOptionsPolicy,
                    provider -> runtimes.require(provider).capabilities());
            RuntimeRequest request = mapping.request();
            warnIfNeeded(request, mapping.ignoredOptions());
            return runtimes.require(request.provider()).stream(request)
                    .<ChatResponse>handle((event, sink) -> {
                        if (event.type() == RuntimeEvent.Type.TEXT_DELTA) {
                            sink.next(RuntimeResponseAccumulator.streamingResponse(
                                    event, request, mapping.ignoredOptions()));
                        }
                        else if (event.type() == RuntimeEvent.Type.COMPLETED) {
                            sink.next(RuntimeResponseAccumulator.streamingResponse(
                                    event, request, mapping.ignoredOptions()));
                        }
                    });
        });
    }

    private void warnIfNeeded(RuntimeRequest request, List<String> ignoredOptions) {
        if (unsupportedOptionsPolicy == SubAuthUnsupportedOptionsPolicy.WARN
                && !ignoredOptions.isEmpty()) {
            LOGGER.warn(
                    "SubAuth ignored unsupported Spring AI options for {}: {}",
                    request.provider(), ignoredOptions);
        }
    }

    @Override
    public ChatOptions getOptions() {
        return defaultOptions;
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return defaultOptions;
    }

}
