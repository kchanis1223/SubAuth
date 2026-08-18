package io.github.kchanis1223.subauth.runtime;

import java.util.Set;

import io.github.kchanis1223.subauth.SubAuthEffort;
import io.github.kchanis1223.subauth.SubAuthProvider;
import reactor.core.publisher.Flux;

public interface RuntimeAdapter extends AutoCloseable {
    SubAuthProvider provider();
    RuntimeProbe probe();
    default RuntimeCapabilities capabilities() {
        return RuntimeCapabilities.textOnly(Set.of(SubAuthEffort.values()));
    }
    Flux<RuntimeEvent> stream(RuntimeRequest request);

    @Override
    default void close() {}
}
