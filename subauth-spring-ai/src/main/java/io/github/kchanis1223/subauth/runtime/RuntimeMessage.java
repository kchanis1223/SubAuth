package io.github.kchanis1223.subauth.runtime;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record RuntimeMessage(
        RuntimeRole role,
        List<RuntimeContent> contents,
        Map<String, Object> metadata) {
    public RuntimeMessage {
        if (role == null) throw new IllegalArgumentException("role is required");
        contents = contents == null ? List.of() : List.copyOf(contents);
        if (contents.isEmpty()) throw new IllegalArgumentException("message contents are required");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static RuntimeMessage text(RuntimeRole role, String text, Map<String, Object> metadata) {
        return new RuntimeMessage(role, List.of(new RuntimeContent.Text(text)), metadata);
    }

    public String text() {
        return contents.stream()
                .filter(RuntimeContent.Text.class::isInstance)
                .map(RuntimeContent.Text.class::cast)
                .map(RuntimeContent.Text::text)
                .collect(Collectors.joining("\n"));
    }
}
