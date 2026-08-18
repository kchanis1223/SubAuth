package io.github.kchanis1223.subauth.runtime;

import io.github.kchanis1223.subauth.SubAuthProvider;
import reactor.core.publisher.Flux;

public interface RuntimeAdapter extends AutoCloseable {
    SubAuthProvider provider();
    RuntimeProbe probe();
    Flux<RuntimeEvent> stream(RuntimeRequest request);

    @Override
    default void close() {}
}
