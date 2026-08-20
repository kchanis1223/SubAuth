package io.github.kchanis1223.subauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kchanis1223.subauth.runtime.RuntimeRegistry;
import io.github.kchanis1223.subauth.runtime.openai.CodexRuntimeAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

@EnabledIfEnvironmentVariable(named = "SUBAUTH_LIVE_TESTS", matches = "true")
class SubAuthOpenAiLiveTest {
    @Test
    void callsChatGptSubscriptionThroughSpringAiChatModel() {
        CodexRuntimeAdapter adapter = new CodexRuntimeAdapter(
                new ObjectMapper(), "codex", Duration.ofSeconds(20));
        try (RuntimeRegistry runtimes = new RuntimeRegistry(List.of(adapter))) {
            SubAuthChatModel model = new SubAuthChatModel(
                    runtimes,
                    SubAuthChatOptions.builder()
                            .provider(SubAuthProvider.OPENAI)
                            .model("auto")
                            .effort(SubAuthEffort.MEDIUM)
                            .build(),
                    Duration.ofMinutes(2));

            String text = model.call(new Prompt("Reply exactly: SUBAUTH_SPRING_OK"))
                    .getResult().getOutput().getText();

            assertThat(text.trim()).isEqualTo("SUBAUTH_SPRING_OK");
        }
    }

    @Test
    void sendsAPngToChatGptSubscriptionThroughSpringAiMedia() throws IOException {
        CodexRuntimeAdapter adapter = new CodexRuntimeAdapter(
                new ObjectMapper(), "codex", Duration.ofSeconds(20));
        try (RuntimeRegistry runtimes = new RuntimeRegistry(List.of(adapter))) {
            SubAuthChatModel model = new SubAuthChatModel(
                    runtimes,
                    SubAuthChatOptions.builder()
                            .provider(SubAuthProvider.OPENAI)
                            .model("auto")
                            .effort(SubAuthEffort.MEDIUM)
                            .build(),
                    Duration.ofMinutes(2));
            Media media = Media.builder()
                    .mimeType(MimeTypeUtils.IMAGE_PNG)
                    .data(new ByteArrayResource(redPng()))
                    .name("red-square.png")
                    .build();
            UserMessage message = UserMessage.builder()
                    .text("Reply exactly IMAGE_OK if the attached image is predominantly red; otherwise reply IMAGE_NOT_RED.")
                    .media(media)
                    .build();

            String text = model.call(new Prompt(List.of(message)))
                    .getResult().getOutput().getText();

            assertThat(text.trim()).isEqualTo("IMAGE_OK");
        }
    }

    @Test
    void executesASpringAiToolCallbackThroughCodexDynamicTools() {
        CodexRuntimeAdapter adapter = new CodexRuntimeAdapter(
                new ObjectMapper(), "codex", Duration.ofSeconds(20));
        try (RuntimeRegistry runtimes = new RuntimeRegistry(List.of(adapter))) {
            SubAuthChatModel model = new SubAuthChatModel(
                    runtimes,
                    SubAuthChatOptions.builder()
                            .provider(SubAuthProvider.OPENAI)
                            .model("auto")
                            .effort(SubAuthEffort.MEDIUM)
                            .build(),
                    Duration.ofMinutes(2));
            AtomicInteger calls = new AtomicInteger();
            ToolCallback callback = new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder()
                            .name("read_magic_number")
                            .description("Returns the current magic number. Always call this tool when asked for it.")
                            .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                            .build();
                }

                @Override
                public String call(String arguments) {
                    calls.incrementAndGet();
                    return "42";
                }
            };
            var options = ToolCallingChatOptions.builder()
                    .toolCallbacks(callback)
                    .build();

            String text = model.call(new Prompt(
                            "Call read_magic_number. If its result is 42, reply exactly: TOOL_OK",
                            options))
                    .getResult().getOutput().getText();

            assertThat(calls.get()).isEqualTo(1);
            assertThat(text.trim()).endsWith("TOOL_OK");
        }
    }

    private byte[] redPng() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int row = 0; row < 64; row++) {
            raw.write(0);
            for (int column = 0; column < 64; column++) {
                raw.write(255);
                raw.write(0);
                raw.write(0);
            }
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            raw.writeTo(deflater);
        }

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(png)) {
            output.write(new byte[] {
                    (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
            });
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            try (DataOutputStream header = new DataOutputStream(headerBytes)) {
                header.writeInt(64);
                header.writeInt(64);
                header.writeByte(8);
                header.writeByte(2);
                header.writeByte(0);
                header.writeByte(0);
                header.writeByte(0);
            }
            writeChunk(output, "IHDR", headerBytes.toByteArray());
            writeChunk(output, "IDAT", compressed.toByteArray());
            writeChunk(output, "IEND", new byte[0]);
        }
        return png.toByteArray();
    }

    private void writeChunk(DataOutputStream output, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        output.writeInt(data.length);
        output.write(typeBytes);
        output.write(data);
        output.writeInt((int) crc.getValue());
    }
}
