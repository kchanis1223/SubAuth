package io.github.kchanis1223.subauth.runtime.openai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.kchanis1223.subauth.SubAuthException;
import io.github.kchanis1223.subauth.runtime.RuntimeContent;
import io.github.kchanis1223.subauth.runtime.RuntimeMessage;
import io.github.kchanis1223.subauth.runtime.RuntimeRequest;
import io.github.kchanis1223.subauth.runtime.RuntimeRole;
import io.github.kchanis1223.subauth.runtime.process.ProcessSupport;

final class CodexTurnInput implements AutoCloseable {
    private final List<Map<String, Object>> values = new ArrayList<>();
    private Path workspace;
    private int mediaCount;

    static CodexTurnInput create(RuntimeRequest request) {
        CodexTurnInput input = new CodexTurnInput();
        try {
            input.render(request);
            return input;
        }
        catch (RuntimeException error) {
            input.close();
            throw error;
        }
    }

    List<Map<String, Object>> values() {
        return List.copyOf(values);
    }

    int mediaCount() {
        return mediaCount;
    }

    private void render(RuntimeRequest request) {
        List<RuntimeMessage> messages = request.messages().stream()
                .filter(message -> message.role() != RuntimeRole.SYSTEM)
                .toList();
        boolean labelRoles = messages.size() != 1 || messages.getFirst().role() != RuntimeRole.USER;
        for (RuntimeMessage message : messages) renderMessage(message, labelRoles);
        if (values.isEmpty()) {
            throw new SubAuthException("empty_runtime_input", "Codex requires text or image input");
        }
    }

    private void renderMessage(RuntimeMessage message, boolean labelRole) {
        boolean roleWritten = !labelRole;
        for (RuntimeContent content : message.contents()) {
            if (content instanceof RuntimeContent.Text text) {
                String value = text.text();
                if (!roleWritten) {
                    value = "[" + message.role().name().toUpperCase(Locale.ROOT) + "]\n" + value;
                    roleWritten = true;
                }
                if (!value.isEmpty()) values.add(Map.of("type", "text", "text", value));
            }
            else if (content instanceof RuntimeContent.Media media) {
                if (!roleWritten) {
                    values.add(Map.of(
                            "type", "text",
                            "text", "[" + message.role().name().toUpperCase(Locale.ROOT) + "]"));
                    roleWritten = true;
                }
                Path path = materialize(media);
                values.add(Map.of("type", "localImage", "path", path.toString()));
            }
        }
    }

    private Path materialize(RuntimeContent.Media media) {
        try {
            if (workspace == null) workspace = createWorkspace();
            String extension = "image/png".equals(media.mimeType()) ? ".png" : ".jpg";
            Path path = workspace.resolve("image-" + (++mediaCount) + extension);
            Files.createFile(path, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
            Files.write(path, media.data(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            return path.toAbsolutePath().normalize();
        }
        catch (IOException error) {
            throw new SubAuthException(
                    "media_materialization_failed", "Could not prepare image input for Codex", error);
        }
    }

    private Path createWorkspace() throws IOException {
        Path directory = Files.createTempDirectory(
                "subauth-codex-media-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        return directory.toAbsolutePath().normalize();
    }

    @Override
    public void close() {
        ProcessSupport.deleteTemporaryDirectory(workspace);
        workspace = null;
    }
}
