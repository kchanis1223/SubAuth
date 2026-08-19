package io.github.kchanis1223.subauth.runtime.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kchanis1223.subauth.SubAuthEffort;
import io.github.kchanis1223.subauth.SubAuthProvider;
import io.github.kchanis1223.subauth.SubAuthUnsupportedCapabilityException;
import io.github.kchanis1223.subauth.runtime.RuntimeContent;
import io.github.kchanis1223.subauth.runtime.RuntimeEvent;
import io.github.kchanis1223.subauth.runtime.RuntimeMessage;
import io.github.kchanis1223.subauth.runtime.RuntimeRequest;
import io.github.kchanis1223.subauth.runtime.RuntimeRole;
import org.junit.jupiter.api.Test;

class CodexRuntimeAdapterTest {
    private static final byte[] PNG = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3
    };

    @Test
    void sendsSpringAiMediaAsCodexLocalImageAndDeletesTheTemporaryFile() throws Exception {
        FakeTransport transport = new FakeTransport(true);
        CodexRuntimeAdapter adapter = new CodexRuntimeAdapter(new ObjectMapper(), transport);
        RuntimeRequest request = imageRequest("vision-model");

        List<RuntimeEvent> events = adapter.stream(request)
                .collectList()
                .block(Duration.ofSeconds(2));

        assertThat(events).isNotNull();
        assertThat(events).extracting(RuntimeEvent::type).containsExactly(
                RuntimeEvent.Type.STARTED,
                RuntimeEvent.Type.TEXT_DELTA,
                RuntimeEvent.Type.COMPLETED);
        assertThat(events.get(1).text()).isEqualTo("IMAGE_OK");
        assertThat(events.getLast().responseMetadata()).containsEntry("mediaCount", 1);

        assertThat(transport.turnInput).hasSize(2);
        assertThat(transport.turnInput.getFirst())
                .containsEntry("type", "text")
                .containsEntry("text", "Describe the attached image.");
        assertThat(transport.turnInput.getLast()).containsEntry("type", "localImage");
        assertThat(transport.imageExistedDuringTurnStart).isTrue();
        assertThat(transport.imageBytes).containsExactly(PNG);
        awaitDeletion(transport.imagePath);
        assertThat(transport.imagePath).doesNotExist();
    }

    @Test
    void rejectsAnImageBeforeStartingAThreadWhenTheModelIsTextOnly() {
        FakeTransport transport = new FakeTransport(false);
        CodexRuntimeAdapter adapter = new CodexRuntimeAdapter(new ObjectMapper(), transport);

        assertThatThrownBy(() -> adapter.stream(imageRequest("text-model"))
                .collectList()
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(SubAuthUnsupportedCapabilityException.class)
                .hasMessageContaining("does not support image input");
        assertThat(transport.threadStarted).isFalse();
        assertThat(transport.imagePath).isNull();
    }

    private static RuntimeRequest imageRequest(String model) {
        RuntimeMessage message = new RuntimeMessage(
                RuntimeRole.USER,
                List.of(
                        new RuntimeContent.Text("Describe the attached image."),
                        new RuntimeContent.Media("image/png", PNG, "sample.png", null)),
                Map.of());
        return new RuntimeRequest(
                SubAuthProvider.OPENAI,
                List.of(message),
                model,
                SubAuthEffort.MEDIUM,
                Duration.ofSeconds(1));
    }

    private static void awaitDeletion(Path path) throws InterruptedException {
        for (int attempt = 0; attempt < 50 && Files.exists(path); attempt++) {
            Thread.sleep(10);
        }
    }

    private static final class FakeTransport implements CodexAppServerTransport {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final boolean imageModel;
        private final Deque<JsonNode> notifications = new ArrayDeque<>();
        private boolean threadStarted;
        private List<Map<String, Object>> turnInput = List.of();
        private Path imagePath;
        private byte[] imageBytes;
        private boolean imageExistedDuringTurnStart;

        private FakeTransport(boolean imageModel) {
            this.imageModel = imageModel;
            notifications.add(json("""
                    {"method":"item/agentMessage/delta","params":{
                      "threadId":"thread-1","turnId":"turn-1","delta":"IMAGE_OK"}}
                    """));
            notifications.add(json("""
                    {"method":"turn/completed","params":{
                      "threadId":"thread-1","turnId":"turn-1",
                      "turn":{"id":"turn-1","status":"completed",
                        "usage":{"inputTokens":10,"outputTokens":2,"totalTokens":12}}}}
                    """));
        }

        @Override
        public JsonNode request(String method, Map<String, ?> params) {
            return switch (method) {
                case "account/read" -> json("""
                        {"account":{"type":"chatgpt","planType":"plus"}}
                        """);
                case "model/list" -> modelList();
                case "thread/start" -> {
                    threadStarted = true;
                    yield json("""
                            {"thread":{"id":"thread-1"},"model":"vision-model"}
                            """);
                }
                case "turn/start" -> captureTurn(params);
                default -> throw new AssertionError("Unexpected method: " + method);
            };
        }

        @Override
        public Subscription subscribe() {
            return new Subscription() {
                @Override
                public JsonNode poll(Duration timeout) {
                    return notifications.pollFirst();
                }

                @Override
                public void close() {}
            };
        }

        @SuppressWarnings("unchecked")
        private JsonNode captureTurn(Map<String, ?> params) {
            turnInput = new ArrayList<>((List<Map<String, Object>>) params.get("input"));
            Map<String, Object> image = turnInput.stream()
                    .filter(item -> "localImage".equals(item.get("type")))
                    .findFirst()
                    .orElseThrow();
            imagePath = Path.of((String) image.get("path"));
            imageExistedDuringTurnStart = Files.isRegularFile(imagePath);
            try {
                imageBytes = Files.readAllBytes(imagePath);
            }
            catch (IOException error) {
                throw new AssertionError(error);
            }
            return json("""
                    {"turn":{"id":"turn-1"}}
                    """);
        }

        private JsonNode modelList() {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("id", imageModel ? "vision-model" : "text-model");
            model.put("isDefault", true);
            model.put("inputModalities", imageModel ? List.of("text", "image") : List.of("text"));
            return objectMapper.valueToTree(Map.of("data", List.of(model)));
        }

        private JsonNode json(String value) {
            try {
                return objectMapper.readTree(value);
            }
            catch (JsonProcessingException error) {
                throw new AssertionError(error);
            }
        }

        @Override
        public void close() {}
    }
}
