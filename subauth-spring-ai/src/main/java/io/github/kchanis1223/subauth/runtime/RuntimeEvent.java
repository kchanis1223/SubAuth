package io.github.kchanis1223.subauth.runtime;

import java.util.Map;

public record RuntimeEvent(Type type, String text, Map<String, Object> metadata, RuntimeUsage usage) {
    public enum Type { STARTED, TEXT_DELTA, COMPLETED }

    public RuntimeEvent {
        text = text == null ? "" : text;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        usage = usage == null ? RuntimeUsage.empty() : usage;
    }

    public static RuntimeEvent started(Map<String, Object> metadata) {
        return new RuntimeEvent(Type.STARTED, "", metadata, RuntimeUsage.empty());
    }

    public static RuntimeEvent delta(String text, Map<String, Object> metadata) {
        return new RuntimeEvent(Type.TEXT_DELTA, text, metadata, RuntimeUsage.empty());
    }

    public static RuntimeEvent completed(Map<String, Object> metadata, RuntimeUsage usage) {
        return new RuntimeEvent(Type.COMPLETED, "", metadata, usage);
    }
}
