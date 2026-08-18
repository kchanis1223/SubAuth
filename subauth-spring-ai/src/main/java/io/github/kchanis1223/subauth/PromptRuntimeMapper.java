package io.github.kchanis1223.subauth;

import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import io.github.kchanis1223.subauth.runtime.RuntimeCapabilities;
import io.github.kchanis1223.subauth.runtime.RuntimeContent;
import io.github.kchanis1223.subauth.runtime.RuntimeMessage;
import io.github.kchanis1223.subauth.runtime.RuntimeOption;
import io.github.kchanis1223.subauth.runtime.RuntimeRequest;
import io.github.kchanis1223.subauth.runtime.RuntimeRole;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

final class PromptRuntimeMapper {
    private PromptRuntimeMapper() {}

    static RuntimeRequest map(
            Prompt prompt,
            SubAuthChatOptions defaults,
            Duration timeout,
            Function<SubAuthProvider, RuntimeCapabilities> capabilityLookup) {
        ChatOptions requestOptions = prompt.getOptions();

        SubAuthProvider provider = defaults.getProvider();
        SubAuthEffort effort = defaults.getEffort();
        String model = defaults.getModel();
        if (requestOptions != null) {
            if (requestOptions.getModel() != null && !requestOptions.getModel().isBlank()) {
                model = requestOptions.getModel();
            }
            if (requestOptions instanceof SubAuthChatOptions subAuthOptions) {
                if (subAuthOptions.getProvider() != null) provider = subAuthOptions.getProvider();
                if (subAuthOptions.getEffort() != null) effort = subAuthOptions.getEffort();
            }
        }
        if (provider == null) {
            throw new SubAuthException("provider_required", "spring.ai.subauth.provider is required");
        }
        RuntimeCapabilities capabilities = capabilityLookup.apply(provider);
        rejectUnsupportedOptions(requestOptions, capabilities);

        List<RuntimeMessage> messages = prompt.getInstructions().stream()
                .map(message -> mapMessage(message, capabilities))
                .toList();
        RuntimeRequest request = new RuntimeRequest(
                provider, messages, normalizeModel(model), effort, timeout);
        capabilities.validate(request);
        return request;
    }

    private static RuntimeMessage mapMessage(Message message, RuntimeCapabilities capabilities) {
        RuntimeRole role = mapRole(message.getMessageType());
        List<RuntimeContent> contents = new ArrayList<>();

        if (message instanceof ToolResponseMessage toolResponseMessage) {
            require(capabilities.toolResults(), "tool response messages");
            toolResponseMessage.getResponses().forEach(response -> contents.add(
                    new RuntimeContent.ToolResult(
                            response.id(), response.name(), response.responseData())));
        }
        else {
            String text = message.getText();
            if (text != null) contents.add(new RuntimeContent.Text(text));
            if (message instanceof MediaContent mediaContent && !mediaContent.getMedia().isEmpty()) {
                require(capabilities.media(), "media content");
                mediaContent.getMedia().stream().map(PromptRuntimeMapper::mapMedia).forEach(contents::add);
            }
            if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
                require(capabilities.toolCalls(), "assistant tool calls");
                assistantMessage.getToolCalls().forEach(toolCall -> contents.add(
                        new RuntimeContent.ToolCall(
                                toolCall.id(), toolCall.type(), toolCall.name(), toolCall.arguments())));
            }
        }
        if (contents.isEmpty()) {
            throw new SubAuthUnsupportedCapabilityException(
                    "SubAuth cannot map an empty Spring AI message");
        }
        return new RuntimeMessage(role, contents, message.getMetadata());
    }

    private static RuntimeContent.Media mapMedia(Media media) {
        return new RuntimeContent.Media(
                media.getMimeType().toString(), media.getDataAsByteArray(), media.getId(), media.getName());
    }

    private static RuntimeRole mapRole(MessageType type) {
        return switch (type) {
            case SYSTEM -> RuntimeRole.SYSTEM;
            case USER -> RuntimeRole.USER;
            case ASSISTANT -> RuntimeRole.ASSISTANT;
            case TOOL -> RuntimeRole.TOOL;
        };
    }

    private static String normalizeModel(String model) {
        return model == null || model.isBlank() || "auto".equalsIgnoreCase(model) ? null : model;
    }

    private static void rejectUnsupportedOptions(
            ChatOptions options, RuntimeCapabilities capabilities) {
        if (options == null) return;
        if (options instanceof ToolCallingChatOptions toolOptions
                && toolOptions.getToolCallbacks() != null
                && !toolOptions.getToolCallbacks().isEmpty()) {
            require(capabilities.supports(RuntimeOption.TOOL_CALLBACKS), "Spring AI tool callbacks");
        }
        if (options.getFrequencyPenalty() != null) requireOption(capabilities, RuntimeOption.FREQUENCY_PENALTY);
        if (options.getMaxTokens() != null) requireOption(capabilities, RuntimeOption.MAX_TOKENS);
        if (options.getPresencePenalty() != null) requireOption(capabilities, RuntimeOption.PRESENCE_PENALTY);
        if (options.getTemperature() != null) requireOption(capabilities, RuntimeOption.TEMPERATURE);
        if (options.getTopK() != null) requireOption(capabilities, RuntimeOption.TOP_K);
        if (options.getTopP() != null) requireOption(capabilities, RuntimeOption.TOP_P);
        if (options.getStopSequences() != null && !options.getStopSequences().isEmpty()) {
            requireOption(capabilities, RuntimeOption.STOP_SEQUENCES);
        }
    }

    private static void requireOption(
            RuntimeCapabilities capabilities, RuntimeOption option) {
        require(capabilities.supports(option), "Spring AI option " + option.name().toLowerCase());
    }

    private static void require(boolean supported, String capability) {
        if (!supported) {
            throw new SubAuthUnsupportedCapabilityException(
                    "The selected subscription runtime does not support " + capability);
        }
    }
}
