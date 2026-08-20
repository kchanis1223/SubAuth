package io.github.kchanis1223.subauth.runtime;

import java.util.Objects;

public record RuntimeTool(
        String name,
        String description,
        String inputSchema,
        Executor executor) {

    public RuntimeTool {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name is required");
        }
        description = description == null ? "" : description;
        if (inputSchema == null || inputSchema.isBlank()) {
            throw new IllegalArgumentException("tool input schema is required");
        }
        executor = Objects.requireNonNull(executor, "tool executor is required");
    }

    public String execute(String arguments) {
        return executor.execute(arguments);
    }

    @FunctionalInterface
    public interface Executor {
        String execute(String arguments);
    }
}
