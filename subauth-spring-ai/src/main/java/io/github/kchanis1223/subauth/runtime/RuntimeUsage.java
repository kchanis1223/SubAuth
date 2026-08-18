package io.github.kchanis1223.subauth.runtime;

import java.util.Map;

public record RuntimeUsage(
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        Long cacheReadTokens,
        Long cacheWriteTokens,
        Map<String, Object> nativeUsage) {

    public RuntimeUsage {
        nativeUsage = nativeUsage == null ? Map.of() : Map.copyOf(nativeUsage);
    }

    public static RuntimeUsage empty() {
        return new RuntimeUsage(null, null, null, null, null, Map.of());
    }
}
