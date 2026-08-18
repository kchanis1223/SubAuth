package io.github.kchanis1223.subauth.runtime;

public enum RuntimeRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL;

    public String wireValue() {
        return name().toLowerCase();
    }
}
