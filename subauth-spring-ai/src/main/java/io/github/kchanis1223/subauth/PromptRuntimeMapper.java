package io.github.kchanis1223.subauth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private static final Set<String> SUPPORTED_IMAGE_TYPES = Set.of("image/png", "image/jpeg");
    private static final int MAX_MEDIA_ITEMS = 4;
    private static final long MAX_MEDIA_ITEM_BYTES = 10L * 1024 * 1024;
    private static final long MAX_MEDIA_TOTAL_BYTES = 20L * 1024 * 1024;

    private PromptRuntimeMapper() {}

    static MappingResult map(
            Prompt prompt,
            SubAuthChatOptions defaults,
            Duration timeout,
            SubAuthUnsupportedOptionsPolicy unsupportedOptionsPolicy,
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
        List<String> ignoredOptions = unsupportedOptions(
                requestOptions, capabilities, unsupportedOptionsPolicy);

        MediaBudget mediaBudget = new MediaBudget();
        List<RuntimeMessage> messages = prompt.getInstructions().stream()
                .map(message -> mapMessage(message, capabilities, mediaBudget))
                .toList();
        RuntimeRequest request = new RuntimeRequest(
                provider, messages, normalizeModel(model), effort, timeout);
        capabilities.validate(request);
        return new MappingResult(request, ignoredOptions);
    }

    private static RuntimeMessage mapMessage(
            Message message, RuntimeCapabilities capabilities, MediaBudget mediaBudget) {
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
                require(role == RuntimeRole.USER, "media content outside user messages");
                mediaContent.getMedia().stream().map(mediaBudget::map).forEach(contents::add);
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

    private static List<String> unsupportedOptions(
            ChatOptions options,
            RuntimeCapabilities capabilities,
            SubAuthUnsupportedOptionsPolicy policy) {
        if (options == null) return List.of();
        if (options instanceof ToolCallingChatOptions toolOptions
                && toolOptions.getToolCallbacks() != null
                && !toolOptions.getToolCallbacks().isEmpty()) {
            require(capabilities.supports(RuntimeOption.TOOL_CALLBACKS), "Spring AI tool callbacks");
        }
        List<String> unsupported = new ArrayList<>();
        if (options.getFrequencyPenalty() != null) {
            collectUnsupported(capabilities, RuntimeOption.FREQUENCY_PENALTY, "frequencyPenalty", unsupported);
        }
        if (options.getMaxTokens() != null) {
            collectUnsupported(capabilities, RuntimeOption.MAX_TOKENS, "maxTokens", unsupported);
        }
        if (options.getPresencePenalty() != null) {
            collectUnsupported(capabilities, RuntimeOption.PRESENCE_PENALTY, "presencePenalty", unsupported);
        }
        if (options.getTemperature() != null) {
            collectUnsupported(capabilities, RuntimeOption.TEMPERATURE, "temperature", unsupported);
        }
        if (options.getTopK() != null) {
            collectUnsupported(capabilities, RuntimeOption.TOP_K, "topK", unsupported);
        }
        if (options.getTopP() != null) {
            collectUnsupported(capabilities, RuntimeOption.TOP_P, "topP", unsupported);
        }
        if (options.getStopSequences() != null && !options.getStopSequences().isEmpty()) {
            collectUnsupported(capabilities, RuntimeOption.STOP_SEQUENCES, "stopSequences", unsupported);
        }
        if (policy == SubAuthUnsupportedOptionsPolicy.REJECT && !unsupported.isEmpty()) {
            throw new SubAuthUnsupportedCapabilityException(
                    "The selected subscription runtime does not support Spring AI options: "
                            + String.join(", ", unsupported));
        }
        return List.copyOf(unsupported);
    }

    private static void collectUnsupported(
            RuntimeCapabilities capabilities,
            RuntimeOption option,
            String optionName,
            List<String> unsupported) {
        if (!capabilities.supports(option)) unsupported.add(optionName);
    }

    private static void require(boolean supported, String capability) {
        if (!supported) {
            throw new SubAuthUnsupportedCapabilityException(
                    "The selected subscription runtime does not support " + capability);
        }
    }

    record MappingResult(RuntimeRequest request, List<String> ignoredOptions) {}

    private static final class MediaBudget {
        private int items;
        private long totalBytes;

        RuntimeContent.Media map(Media media) {
            if (++items > MAX_MEDIA_ITEMS) {
                throw new SubAuthException(
                        "too_many_media_items",
                        "SubAuth accepts at most " + MAX_MEDIA_ITEMS + " images per request");
            }
            String mimeType = media.getMimeType().toString().toLowerCase(Locale.ROOT);
            if (!SUPPORTED_IMAGE_TYPES.contains(mimeType)) {
                throw new SubAuthUnsupportedCapabilityException(
                        "SubAuth currently supports only PNG and JPEG image input, not " + mimeType);
            }
            byte[] data = media.getDataAsByteArray();
            if (data.length == 0) {
                throw new SubAuthException("empty_media", "SubAuth received an empty image");
            }
            if (data.length > MAX_MEDIA_ITEM_BYTES) {
                throw new SubAuthException(
                        "media_too_large", "Each SubAuth image must be 10 MiB or smaller");
            }
            totalBytes += data.length;
            if (totalBytes > MAX_MEDIA_TOTAL_BYTES) {
                throw new SubAuthException(
                        "media_total_too_large", "SubAuth image input must total 20 MiB or less");
            }
            if (!hasExpectedSignature(mimeType, data)) {
                throw new SubAuthException(
                        "invalid_media_data", "Image bytes do not match declared MIME type " + mimeType);
            }
            return new RuntimeContent.Media(mimeType, data, media.getId(), media.getName());
        }

        private boolean hasExpectedSignature(String mimeType, byte[] data) {
            if ("image/png".equals(mimeType)) {
                byte[] signature = new byte[] {
                        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a };
                if (data.length < signature.length) return false;
                for (int index = 0; index < signature.length; index++) {
                    if (data[index] != signature[index]) return false;
                }
                return true;
            }
            return data.length >= 3
                    && data[0] == (byte) 0xff
                    && data[1] == (byte) 0xd8
                    && data[2] == (byte) 0xff;
        }
    }
}
