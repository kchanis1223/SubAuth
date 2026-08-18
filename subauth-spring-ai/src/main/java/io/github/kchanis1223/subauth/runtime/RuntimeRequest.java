package io.github.kchanis1223.subauth.runtime;

import java.time.Duration;
import java.util.List;

import io.github.kchanis1223.subauth.SubAuthEffort;
import io.github.kchanis1223.subauth.SubAuthProvider;

public record RuntimeRequest(
        SubAuthProvider provider,
        List<RuntimeMessage> messages,
        String model,
        SubAuthEffort effort,
        Duration timeout) {

    public RuntimeRequest {
        if (provider == null) throw new IllegalArgumentException("provider is required");
        messages = List.copyOf(messages);
        if (messages.isEmpty()) throw new IllegalArgumentException("messages are required");
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }
}
