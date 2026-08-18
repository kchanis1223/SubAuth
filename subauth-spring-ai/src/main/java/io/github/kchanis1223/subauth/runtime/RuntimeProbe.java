package io.github.kchanis1223.subauth.runtime;

import java.util.List;
import java.util.Map;

import io.github.kchanis1223.subauth.SubAuthProvider;

public record RuntimeProbe(
        SubAuthProvider provider,
        boolean available,
        boolean subscriptionReady,
        String version,
        String detail,
        List<String> models,
        Map<String, Object> metadata) {

    public RuntimeProbe {
        models = models == null ? List.of() : List.copyOf(models);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
