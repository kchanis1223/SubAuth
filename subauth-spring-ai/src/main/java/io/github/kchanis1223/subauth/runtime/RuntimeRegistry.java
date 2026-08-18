package io.github.kchanis1223.subauth.runtime;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

import io.github.kchanis1223.subauth.SubAuthException;
import io.github.kchanis1223.subauth.SubAuthProvider;

public final class RuntimeRegistry implements AutoCloseable {
    private final Map<SubAuthProvider, RuntimeAdapter> adapters = new EnumMap<>(SubAuthProvider.class);

    public RuntimeRegistry(Collection<? extends RuntimeAdapter> adapters) {
        for (RuntimeAdapter adapter : adapters) {
            if (this.adapters.put(adapter.provider(), adapter) != null) {
                throw new IllegalArgumentException("Duplicate runtime adapter: " + adapter.provider());
            }
        }
    }

    public RuntimeAdapter require(SubAuthProvider provider) {
        RuntimeAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            throw new SubAuthException("unknown_provider", "No runtime adapter is registered for " + provider);
        }
        return adapter;
    }

    public Collection<RuntimeAdapter> adapters() {
        return adapters.values();
    }

    @Override
    public void close() {
        for (RuntimeAdapter adapter : adapters.values()) {
            try {
                adapter.close();
            }
            catch (Exception ignored) {
                // Continue closing the remaining provider runtimes.
            }
        }
    }
}
