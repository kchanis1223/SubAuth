package io.github.kchanis1223.subauth.runtime.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kchanis1223.subauth.SubAuthException;
import reactor.core.publisher.Flux;

public final class ProcessSupport {
    private ProcessSupport() {}

    public static Map<String, String> environmentWithout(Set<String> removedKeys) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        removedKeys.forEach(environment::remove);
        return environment;
    }

    public static Capture capture(
            List<String> command,
            Map<String, String> environment,
            Path directory,
            Duration timeout,
            boolean allowFailure) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().clear();
            builder.environment().putAll(environment);
            if (directory != null) builder.directory(directory.toFile());
            process = builder.start();
            process.getOutputStream().close();
            AtomicReference<byte[]> stdoutBytes = new AtomicReference<>(new byte[0]);
            AtomicReference<byte[]> stderrBytes = new AtomicReference<>(new byte[0]);
            Process activeProcess = process;
            Thread stdoutReader = Thread.ofVirtual().start(() ->
                    stdoutBytes.set(readAllBytes(activeProcess.getInputStream())));
            Thread stderrReader = Thread.ofVirtual().start(() ->
                    stderrBytes.set(readAllBytes(activeProcess.getErrorStream())));
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor();
                stdoutReader.join();
                stderrReader.join();
                throw new SubAuthException("runtime_timeout", "Runtime command timed out");
            }
            stdoutReader.join();
            stderrReader.join();
            String stdout = new String(stdoutBytes.get(), StandardCharsets.UTF_8).trim();
            String stderr = new String(stderrBytes.get(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 && !allowFailure) {
                throw new SubAuthException(
                        "runtime_command_failed",
                        "Runtime command exited with status " + process.exitValue());
            }
            return new Capture(process.exitValue(), stdout, stderr);
        }
        catch (IOException error) {
            throw new SubAuthException("runtime_unavailable", "Could not start runtime: " + command.getFirst(), error);
        }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            throw new SubAuthException("runtime_interrupted", "Runtime command was interrupted", error);
        }
    }

    public static Flux<JsonNode> streamJsonLines(
            ObjectMapper objectMapper,
            List<String> command,
            Map<String, String> environment,
            Path directory,
            Duration timeout) {
        return Flux.create(sink -> {
            AtomicReference<Process> processRef = new AtomicReference<>();
            AtomicBoolean timedOut = new AtomicBoolean();
            Thread worker = Thread.ofVirtual().name("subauth-runtime-stream").start(() -> {
                Process process = null;
                Thread timeoutThread = null;
                Thread stderrThread = null;
                try {
                    ProcessBuilder builder = new ProcessBuilder(command);
                    builder.environment().clear();
                    builder.environment().putAll(environment);
                    if (directory != null) builder.directory(directory.toFile());
                    process = builder.start();
                    processRef.set(process);
                    process.getOutputStream().close();
                    Process managedProcessForStderr = process;
                    stderrThread = Thread.ofVirtual().name("subauth-runtime-stderr").start(() ->
                            readAllBytes(managedProcessForStderr.getErrorStream()));

                    Process managedProcess = process;
                    timeoutThread = Thread.ofVirtual().name("subauth-runtime-timeout").start(() -> {
                        try {
                            Thread.sleep(timeout);
                            if (managedProcess.isAlive()) {
                                timedOut.set(true);
                                managedProcess.destroy();
                            }
                        }
                        catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    });

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while (!sink.isCancelled() && (line = reader.readLine()) != null) {
                            if (!line.isBlank()) sink.next(objectMapper.readTree(line));
                        }
                    }
                    int exitCode = process.waitFor();
                    if (sink.isCancelled()) return;
                    if (timedOut.get()) {
                        sink.error(new SubAuthException("runtime_timeout", "Subscription runtime request timed out"));
                    }
                    else if (exitCode != 0) {
                        sink.error(new SubAuthException(
                                "runtime_request_failed", "Subscription runtime exited with status " + exitCode));
                    }
                    else {
                        sink.complete();
                    }
                }
                catch (Exception error) {
                    if (!sink.isCancelled()) {
                        sink.error(error instanceof SubAuthException ? error :
                                new SubAuthException("runtime_protocol_error", "Invalid runtime stream", error));
                    }
                }
                finally {
                    if (timeoutThread != null) timeoutThread.interrupt();
                    if (stderrThread != null) stderrThread.interrupt();
                    if (process != null && process.isAlive()) process.destroyForcibly();
                    processRef.set(null);
                }
            });
            sink.onCancel(() -> {
                Process process = processRef.get();
                if (process != null && process.isAlive()) process.destroy();
                worker.interrupt();
            });
        });
    }

    public static Path temporaryDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        }
        catch (IOException error) {
            throw new SubAuthException("temporary_directory_failed", "Could not create runtime workspace", error);
        }
    }

    public static void deleteTemporaryDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            List<Path> ordered = new ArrayList<>(paths.sorted((left, right) -> right.compareTo(left)).toList());
            for (Path path : ordered) Files.deleteIfExists(path);
        }
        catch (IOException ignored) {
            // The OS temporary directory can clean up a workspace that a child still holds.
        }
    }

    public record Capture(int exitCode, String stdout, String stderr) {}

    private static byte[] readAllBytes(java.io.InputStream stream) {
        try {
            return stream.readAllBytes();
        }
        catch (IOException ignored) {
            return new byte[0];
        }
    }
}
