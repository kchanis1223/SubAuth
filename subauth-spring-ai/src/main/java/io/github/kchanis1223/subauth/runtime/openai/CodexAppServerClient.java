package io.github.kchanis1223.subauth.runtime.openai;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kchanis1223.subauth.SubAuthException;

public final class CodexAppServerClient implements CodexAppServerTransport {
    private final ObjectMapper objectMapper;
    private final List<String> command;
    private final Duration requestTimeout;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Set<BlockingQueue<JsonNode>> subscribers = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();
    private final Object writeLock = new Object();

    private volatile Process process;
    private volatile BufferedWriter writer;

    public CodexAppServerClient(ObjectMapper objectMapper, String executable, Duration requestTimeout) {
        this.objectMapper = objectMapper;
        this.command = List.of(executable, "app-server", "--stdio");
        this.requestTimeout = requestTimeout;
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    @Override
    public JsonNode request(String method, Map<String, ?> params) {
        ensureStarted();
        return requestInternal(method, params);
    }

    @Override
    public void respond(JsonNode id, Map<String, ?> result) {
        ensureStarted();
        if (id == null || (!id.isNumber() && !id.isTextual())) {
            throw new SubAuthException(
                    "runtime_protocol_error", "Codex App Server request id is invalid");
        }
        write(Map.of("id", id, "result", result));
    }

    @Override
    public Subscription subscribe() {
        ensureStarted();
        BlockingQueue<JsonNode> queue = new LinkedBlockingQueue<>();
        subscribers.add(queue);
        return new Subscription(queue);
    }

    private void ensureStarted() {
        if (isRunning()) return;
        synchronized (lifecycleLock) {
            if (isRunning()) return;
            try {
                Process started = new ProcessBuilder(command).start();
                process = started;
                writer = new BufferedWriter(new OutputStreamWriter(
                        started.getOutputStream(), StandardCharsets.UTF_8));
                Thread.ofVirtual().name("subauth-codex-stdout").start(() -> readStdout(started));
                Thread.ofVirtual().name("subauth-codex-stderr").start(() -> drainStderr(started));
                requestInternal("initialize", Map.of(
                        "clientInfo", Map.of(
                                "name", "subauth", "title", "SubAuth", "version", "0.2.0"),
                        "capabilities", Map.of("experimentalApi", true)));
                notifyInternal("initialized", Map.of());
            }
            catch (IOException error) {
                close();
                throw new SubAuthException(
                        "runtime_unavailable", "Could not start Codex App Server", error);
            }
            catch (RuntimeException error) {
                close();
                throw error;
            }
        }
    }

    private JsonNode requestInternal(String method, Map<String, ?> params) {
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            write(Map.of("id", id, "method", method, "params", params));
            JsonNode response = future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.has("error")) {
                throw new SubAuthException(
                        "runtime_protocol_error", "Codex App Server rejected " + method);
            }
            JsonNode result = response.get("result");
            if (result == null || !result.isObject()) {
                throw new SubAuthException(
                        "runtime_protocol_error", "Codex App Server returned an invalid result for " + method);
            }
            return result;
        }
        catch (SubAuthException error) {
            throw error;
        }
        catch (Exception error) {
            throw new SubAuthException(
                    "runtime_protocol_error", "Codex App Server request failed: " + method, error);
        }
        finally {
            pending.remove(id);
        }
    }

    private void notifyInternal(String method, Map<String, ?> params) {
        write(Map.of("method", method, "params", params));
    }

    private void write(Map<String, ?> message) {
        BufferedWriter activeWriter = writer;
        if (activeWriter == null) {
            throw new SubAuthException("runtime_unavailable", "Codex App Server stdin is unavailable");
        }
        synchronized (writeLock) {
            try {
                activeWriter.write(objectMapper.writeValueAsString(message));
                activeWriter.newLine();
                activeWriter.flush();
            }
            catch (IOException error) {
                throw new SubAuthException("runtime_protocol_error", "Could not write to Codex App Server", error);
            }
        }
    }

    private void readStdout(Process started) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                started.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode message = objectMapper.readTree(line);
                JsonNode idNode = message.get("id");
                if (message.path("method").isTextual()) {
                    subscribers.forEach(queue -> queue.offer(message));
                }
                else if (idNode != null && idNode.canConvertToLong()) {
                    CompletableFuture<JsonNode> future = pending.get(idNode.longValue());
                    if (future != null) future.complete(message);
                }
            }
            failPending(new SubAuthException("runtime_unavailable", "Codex App Server stopped"));
        }
        catch (Exception error) {
            failPending(new SubAuthException(
                    "runtime_protocol_error", "Codex App Server emitted invalid JSON", error));
        }
    }

    private void drainStderr(Process started) {
        try {
            started.getErrorStream().transferTo(java.io.OutputStream.nullOutputStream());
        }
        catch (IOException ignored) {
            // The process may close stderr while shutting down.
        }
    }

    private void failPending(SubAuthException error) {
        pending.values().forEach(future -> future.completeExceptionally(error));
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            Process active = process;
            process = null;
            writer = null;
            subscribers.clear();
            if (active != null && active.isAlive()) {
                active.destroy();
                try {
                    if (!active.waitFor(2, TimeUnit.SECONDS)) active.destroyForcibly();
                }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    active.destroyForcibly();
                }
            }
            failPending(new SubAuthException("runtime_unavailable", "Codex App Server closed"));
            pending.clear();
        }
    }

    public final class Subscription implements CodexAppServerTransport.Subscription {
        private final BlockingQueue<JsonNode> queue;

        private Subscription(BlockingQueue<JsonNode> queue) {
            this.queue = queue;
        }

        public JsonNode poll(Duration timeout) throws InterruptedException {
            return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            subscribers.remove(queue);
        }
    }
}
