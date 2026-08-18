package io.github.kchanis1223.subauth.runtime;

import java.util.Map;

public record RuntimeEvent(
        Type type,
        int generationIndex,
        String text,
        Map<String, Object> messageMetadata,
        Map<String, Object> responseMetadata,
        RuntimeUsage usage,
        String finishReason) {
    public enum Type { STARTED, TEXT_DELTA, COMPLETED }

    public RuntimeEvent {
        if (type == null) throw new IllegalArgumentException("type is required");
        if (generationIndex < 0) throw new IllegalArgumentException("generationIndex must not be negative");
        text = text == null ? "" : text;
        messageMetadata = messageMetadata == null ? Map.of() : Map.copyOf(messageMetadata);
        responseMetadata = responseMetadata == null ? Map.of() : Map.copyOf(responseMetadata);
        usage = usage == null ? RuntimeUsage.empty() : usage;
        finishReason = finishReason == null || finishReason.isBlank() ? null : finishReason;
    }

    public static RuntimeEvent started(Map<String, Object> responseMetadata) {
        return new RuntimeEvent(
                Type.STARTED, 0, "", Map.of(), responseMetadata, RuntimeUsage.empty(), null);
    }

    public static RuntimeEvent delta(String text, Map<String, Object> responseMetadata) {
        return delta(0, text, Map.of(), responseMetadata);
    }

    public static RuntimeEvent delta(
            int generationIndex,
            String text,
            Map<String, Object> messageMetadata,
            Map<String, Object> responseMetadata) {
        return new RuntimeEvent(
                Type.TEXT_DELTA, generationIndex, text, messageMetadata,
                responseMetadata, RuntimeUsage.empty(), null);
    }

    public static RuntimeEvent completed(Map<String, Object> responseMetadata, RuntimeUsage usage) {
        return completed(0, Map.of(), responseMetadata, usage, null);
    }

    public static RuntimeEvent completed(
            Map<String, Object> responseMetadata,
            RuntimeUsage usage,
            String finishReason) {
        return completed(0, Map.of(), responseMetadata, usage, finishReason);
    }

    public static RuntimeEvent completed(
            int generationIndex,
            Map<String, Object> messageMetadata,
            Map<String, Object> responseMetadata,
            RuntimeUsage usage,
            String finishReason) {
        return new RuntimeEvent(
                Type.COMPLETED, generationIndex, "", messageMetadata,
                responseMetadata, usage, finishReason);
    }
}
