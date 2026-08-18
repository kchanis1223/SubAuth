package io.github.kchanis1223.subauth;

import java.time.Duration;
import java.util.List;

import io.github.kchanis1223.subauth.runtime.RuntimeMessage;
import io.github.kchanis1223.subauth.runtime.RuntimeRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

final class PromptRuntimeMapper {
    private PromptRuntimeMapper() {}

    static RuntimeRequest map(Prompt prompt, SubAuthChatOptions defaults, Duration timeout) {
        ChatOptions requestOptions = prompt.getOptions();
        rejectUnsupportedOptions(requestOptions);

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

        List<RuntimeMessage> messages = prompt.getInstructions().stream()
                .map(PromptRuntimeMapper::mapMessage)
                .toList();
        return new RuntimeRequest(provider, messages, normalizeModel(model), effort, timeout);
    }

    private static RuntimeMessage mapMessage(Message message) {
        String role = message.getMessageType().getValue();
        String text = message.getText();
        if (text == null) {
            throw new SubAuthUnsupportedCapabilityException(
                    "SubAuth currently supports text message content only");
        }
        return new RuntimeMessage(role, text);
    }

    private static String normalizeModel(String model) {
        return model == null || model.isBlank() || "auto".equalsIgnoreCase(model) ? null : model;
    }

    private static void rejectUnsupportedOptions(ChatOptions options) {
        if (options == null) return;
        if (options instanceof ToolCallingChatOptions toolOptions
                && toolOptions.getToolCallbacks() != null
                && !toolOptions.getToolCallbacks().isEmpty()) {
            throw new SubAuthUnsupportedCapabilityException(
                    "Spring AI tool callbacks are not supported by subscription runtimes yet");
        }
        if (options.getFrequencyPenalty() != null
                || options.getMaxTokens() != null
                || options.getPresencePenalty() != null
                || options.getTemperature() != null
                || options.getTopK() != null
                || options.getTopP() != null
                || (options.getStopSequences() != null && !options.getStopSequences().isEmpty())) {
            throw new SubAuthUnsupportedCapabilityException(
                    "This subscription transport currently supports provider, model, and effort options only");
        }
    }
}
