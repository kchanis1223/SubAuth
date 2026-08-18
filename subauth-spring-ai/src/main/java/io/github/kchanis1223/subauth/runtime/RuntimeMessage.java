package io.github.kchanis1223.subauth.runtime;

public record RuntimeMessage(String role, String text) {
    public RuntimeMessage {
        if (role == null || role.isBlank()) throw new IllegalArgumentException("role is required");
        if (text == null) throw new IllegalArgumentException("text is required");
    }
}
