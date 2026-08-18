package io.github.kchanis1223.subauth;

public final class SubAuthUnsupportedCapabilityException extends SubAuthException {
    public SubAuthUnsupportedCapabilityException(String message) {
        super("unsupported_capability", message);
    }
}
