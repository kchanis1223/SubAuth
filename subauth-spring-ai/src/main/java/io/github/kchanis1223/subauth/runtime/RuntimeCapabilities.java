package io.github.kchanis1223.subauth.runtime;

import java.util.Set;

import io.github.kchanis1223.subauth.SubAuthEffort;
import io.github.kchanis1223.subauth.SubAuthUnsupportedCapabilityException;

public record RuntimeCapabilities(
        boolean text,
        boolean systemMessages,
        boolean assistantMessages,
        boolean media,
        boolean toolCalls,
        boolean toolResults,
        Set<SubAuthEffort> efforts,
        Set<RuntimeOption> options) {

    public RuntimeCapabilities {
        efforts = efforts == null ? Set.of() : Set.copyOf(efforts);
        options = options == null ? Set.of() : Set.copyOf(options);
    }

    public static RuntimeCapabilities textOnly(Set<SubAuthEffort> efforts) {
        return new RuntimeCapabilities(
                true, true, true, false, false, false,
                efforts, Set.of(RuntimeOption.MODEL, RuntimeOption.EFFORT));
    }

    public boolean supports(RuntimeOption option) {
        return options.contains(option);
    }

    public void validate(RuntimeRequest request) {
        if (request.model() != null && !supports(RuntimeOption.MODEL)) {
            unsupported("model selection");
        }
        if (request.effort() != null
                && (!supports(RuntimeOption.EFFORT) || !efforts.contains(request.effort()))) {
            unsupported("effort=" + request.effort().cliValue());
        }
        if (!request.tools().isEmpty() && !supports(RuntimeOption.TOOL_CALLBACKS)) {
            unsupported("Spring AI tool callbacks");
        }
        for (RuntimeMessage message : request.messages()) {
            if (message.role() == RuntimeRole.SYSTEM && !systemMessages) unsupported("system messages");
            if (message.role() == RuntimeRole.ASSISTANT && !assistantMessages) unsupported("assistant messages");
            if (message.role() == RuntimeRole.TOOL && !toolResults) unsupported("tool response messages");
            for (RuntimeContent content : message.contents()) {
                if (content instanceof RuntimeContent.Text && !text) unsupported("text content");
                if (content instanceof RuntimeContent.Media && !media) unsupported("media content");
                if (content instanceof RuntimeContent.ToolCall && !toolCalls) unsupported("tool calls");
                if (content instanceof RuntimeContent.ToolResult && !toolResults) unsupported("tool results");
            }
        }
    }

    private static void unsupported(String capability) {
        throw new SubAuthUnsupportedCapabilityException(
                "The selected subscription runtime does not support " + capability);
    }
}
