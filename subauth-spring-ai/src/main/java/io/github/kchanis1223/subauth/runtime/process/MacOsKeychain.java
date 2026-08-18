package io.github.kchanis1223.subauth.runtime.process;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class MacOsKeychain {
    private static final String SERVICE = "io.github.kchanis1223.subauth.credentials";

    public String read(String account) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return null;
        ProcessSupport.Capture capture = ProcessSupport.capture(
                List.of("security", "find-generic-password", "-a", account, "-s", SERVICE, "-w"),
                Map.copyOf(System.getenv()), null, Duration.ofSeconds(5), true);
        return capture.exitCode() == 0 && !capture.stdout().isBlank() ? capture.stdout() : null;
    }
}
