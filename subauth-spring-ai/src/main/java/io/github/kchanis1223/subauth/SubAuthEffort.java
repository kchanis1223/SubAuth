package io.github.kchanis1223.subauth;

public enum SubAuthEffort {
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX;

    public String cliValue() {
        return name().toLowerCase();
    }
}
