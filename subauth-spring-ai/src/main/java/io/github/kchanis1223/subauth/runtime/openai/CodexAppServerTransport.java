package io.github.kchanis1223.subauth.runtime.openai;

import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

interface CodexAppServerTransport extends AutoCloseable {
    JsonNode request(String method, Map<String, ?> params);

    Subscription subscribe();

    @Override
    void close();

    interface Subscription extends AutoCloseable {
        JsonNode poll(Duration timeout) throws InterruptedException;

        @Override
        void close();
    }
}
