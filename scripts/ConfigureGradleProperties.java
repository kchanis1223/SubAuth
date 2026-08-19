import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigureGradleProperties {
    private static final String USER_KEY = "subauthGithubUser";
    private static final String TOKEN_KEY = "subauthGithubToken";
    private static final Pattern MANAGED_PROPERTY = Pattern.compile(
            "^\\s*(" + USER_KEY + "|" + TOKEN_KEY + ")\\s*(?:=|:|\\s).*$");
    private static final DateTimeFormatter BACKUP_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private ConfigureGradleProperties() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "usage: ConfigureGradleProperties <gradle.properties> <github-user>");
        }

        String token = requireSingleLine(
                System.getenv("SUBAUTH_SETUP_GITHUB_TOKEN"), "SUBAUTH_SETUP_GITHUB_TOKEN");
        String username = requireSingleLine(args[1], "github-user");
        Path properties = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(properties.getParent());

        List<String> original = Files.exists(properties)
                ? Files.readAllLines(properties, StandardCharsets.UTF_8)
                : List.of();
        List<String> updated = upsert(original, username, token);

        if (Files.exists(properties)) {
            Path backup = properties.resolveSibling(
                    properties.getFileName()
                            + ".subauth-backup-"
                            + BACKUP_TIME.format(Instant.now()));
            Files.copy(properties, backup, StandardCopyOption.COPY_ATTRIBUTES);
            restrictPermissions(backup);
            System.out.println("Backed up existing Gradle properties to " + backup);
        }

        writeAtomically(updated, properties);
        restrictPermissions(properties);
        System.out.println("Configured SubAuth GitHub Packages credentials in " + properties);
    }

    private static List<String> upsert(
            List<String> original, String username, String token) {
        List<String> updated = new ArrayList<>();
        Set<String> written = new HashSet<>();

        for (String line : original) {
            Matcher matcher = MANAGED_PROPERTY.matcher(line);
            if (!matcher.matches()) {
                updated.add(line);
                continue;
            }

            String key = matcher.group(1);
            if (written.add(key)) {
                updated.add(key + "=" + valueFor(key, username, token));
            }
        }

        boolean anyMissing = !written.contains(USER_KEY) || !written.contains(TOKEN_KEY);
        if (anyMissing && !updated.isEmpty() && !updated.getLast().isBlank()) {
            updated.add("");
        }
        if (anyMissing && written.isEmpty()) {
            updated.add("# SubAuth GitHub Packages credentials");
        }
        if (!written.contains(USER_KEY)) {
            updated.add(USER_KEY + "=" + username);
        }
        if (!written.contains(TOKEN_KEY)) {
            updated.add(TOKEN_KEY + "=" + token);
        }
        return List.copyOf(updated);
    }

    private static String valueFor(String key, String username, String token) {
        return USER_KEY.equals(key) ? username : token;
    }

    private static void writeAtomically(List<String> lines, Path properties) throws IOException {
        Path temporary = Files.createTempFile(properties.getParent(), "subauth-gradle-", ".properties");
        try {
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        properties,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, properties, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restrictPermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(
                    path,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems retain their platform default permissions.
        }
    }

    private static String requireSingleLine(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(name + " must be a single line");
        }
        return value;
    }
}
