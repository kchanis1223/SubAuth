package io.github.kchanis1223.subauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.github.kchanis1223.subauth.runtime.RuntimeAdapter;
import io.github.kchanis1223.subauth.runtime.RuntimeEvent;
import io.github.kchanis1223.subauth.runtime.RuntimeProbe;
import io.github.kchanis1223.subauth.runtime.RuntimeRegistry;
import io.github.kchanis1223.subauth.runtime.RuntimeRequest;
import io.github.kchanis1223.subauth.runtime.RuntimeUsage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

class SubAuthChatModelTest {

    @Test
    void callReturnsSpringAiChatResponseAndPreservesConversation() {
        AtomicReference<RuntimeRequest> captured = new AtomicReference<>();
        RuntimeAdapter runtime = runtime(captured);
        SubAuthChatModel model = model(runtime);

        var response = model.call(new Prompt(List.of(
                new SystemMessage("Be exact"),
                new UserMessage("first"),
                new AssistantMessage("previous"),
                UserMessage.builder()
                        .text("current")
                        .metadata(Map.of("requestTag", "current-turn"))
                        .build())));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("SUBAUTH_OK");
        assertThat(response.getMetadata().getModel()).isEqualTo("runtime-model");
        assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(4);
        assertThat(response.getMetadata().getUsage().getCacheReadInputTokens()).isEqualTo(1L);
        assertThat(response.getMetadata().getUsage().getCacheWriteInputTokens()).isEqualTo(2L);
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("end_turn");
        assertThat((Object) response.getResult().getOutput().getMetadata().get("reasoningContent"))
                .isEqualTo("test-reasoning");
        assertThat((Object) response.getMetadata().get("runtimeField")).isEqualTo("runtime-value");
        assertThat(response.getResult().getMetadata().containsKey("runtimeField")).isFalse();
        assertThat(captured.get().messages())
                .extracting(message -> message.role().wireValue() + ":" + message.text())
                .containsExactly("system:Be exact", "user:first", "assistant:previous", "user:current");
        assertThat((Object) captured.get().messages().getLast().metadata().get("requestTag"))
                .isEqualTo("current-turn");
    }

    @Test
    void streamUsesSpringAiFluxResponses() {
        SubAuthChatModel model = model(runtime(new AtomicReference<>()));

        List<org.springframework.ai.chat.model.ChatResponse> responses = model.stream(new Prompt("hello"))
                .collectList()
                .block(Duration.ofSeconds(2));

        assertThat(responses).isNotNull();
        assertThat(responses).extracting(response -> response.getResult().getOutput().getText())
                .containsExactly("SUB", "AUTH_OK", "");
        assertThat(responses.getLast().getResult().getMetadata().getFinishReason())
                .isEqualTo("end_turn");
        assertThat(responses.getLast().getMetadata().getUsage().getTotalTokens()).isEqualTo(6);
    }

    @Test
    void providerCanBeOverriddenPerPrompt() {
        AtomicReference<RuntimeRequest> captured = new AtomicReference<>();
        RuntimeAdapter claude = runtime(captured, SubAuthProvider.CLAUDE);
        SubAuthChatModel model = new SubAuthChatModel(
                new RuntimeRegistry(List.of(claude)),
                SubAuthChatOptions.builder().provider(SubAuthProvider.OPENAI).build(),
                Duration.ofSeconds(2));

        model.call(new Prompt("hello", SubAuthChatOptions.builder()
                .provider(SubAuthProvider.CLAUDE)
                .model("sonnet")
                .effort(SubAuthEffort.HIGH)
                .build()));

        assertThat(captured.get().provider()).isEqualTo(SubAuthProvider.CLAUDE);
        assertThat(captured.get().model()).isEqualTo("sonnet");
        assertThat(captured.get().effort()).isEqualTo(SubAuthEffort.HIGH);
    }

    @Test
    void unsupportedPortableOptionsAreIgnoredByDefaultAndReportedInMetadata() {
        AtomicReference<RuntimeRequest> captured = new AtomicReference<>();
        SubAuthChatModel model = model(runtime(captured));
        ChatOptions options = ChatOptions.builder()
                .temperature(0.3)
                .maxTokens(128)
                .topP(0.9)
                .stopSequences(List.of("STOP"))
                .build();

        var response = model.call(new Prompt("hello", options));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("SUBAUTH_OK");
        assertThat(captured.get()).isNotNull();
        assertThat((Object) response.getMetadata().get("ignoredOptions"))
                .isEqualTo(List.of("maxTokens", "temperature", "topP", "stopSequences"));
    }

    @Test
    void unsupportedPortableOptionsCanStillBeRejected() {
        RuntimeAdapter runtime = runtime(new AtomicReference<>());
        SubAuthChatModel model = new SubAuthChatModel(
                new RuntimeRegistry(List.of(runtime)),
                SubAuthChatOptions.builder().provider(runtime.provider()).build(),
                Duration.ofSeconds(2),
                SubAuthUnsupportedOptionsPolicy.REJECT);
        ChatOptions options = ChatOptions.builder().temperature(0.3).build();

        assertThatThrownBy(() -> model.call(new Prompt("hello", options)))
                .isInstanceOf(SubAuthUnsupportedCapabilityException.class)
                .hasMessageContaining("temperature");
    }

    @Test
    void rejectsMediaBeforeStartingATextOnlyRuntime() {
        AtomicReference<RuntimeRequest> captured = new AtomicReference<>();
        SubAuthChatModel model = model(runtime(captured));
        Media media = Media.builder()
                .mimeType(MimeTypeUtils.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[] { 1, 2, 3 }))
                .build();
        UserMessage message = UserMessage.builder().text("describe").media(media).build();

        assertThatThrownBy(() -> model.call(new Prompt(List.of(message))))
                .isInstanceOf(SubAuthUnsupportedCapabilityException.class)
                .hasMessageContaining("media content");
        assertThat(captured.get()).isNull();
    }

    @Test
    void doesNotFabricateAMissingFinishReason() {
        SubAuthChatModel model = model(runtime(
                new AtomicReference<>(), SubAuthProvider.OPENAI, null));

        var response = model.call(new Prompt("hello"));

        assertThat(response.getResult().getMetadata().getFinishReason()).isNull();
    }

    private SubAuthChatModel model(RuntimeAdapter runtime) {
        return new SubAuthChatModel(
                new RuntimeRegistry(List.of(runtime)),
                SubAuthChatOptions.builder()
                        .provider(runtime.provider())
                        .model("auto")
                        .effort(SubAuthEffort.MEDIUM)
                        .build(),
                Duration.ofSeconds(2));
    }

    private RuntimeAdapter runtime(AtomicReference<RuntimeRequest> captured) {
        return runtime(captured, SubAuthProvider.OPENAI);
    }

    private RuntimeAdapter runtime(
            AtomicReference<RuntimeRequest> captured, SubAuthProvider provider) {
        return runtime(captured, provider, "end_turn");
    }

    private RuntimeAdapter runtime(
            AtomicReference<RuntimeRequest> captured,
            SubAuthProvider provider,
            String finishReason) {
        return new RuntimeAdapter() {
            @Override public SubAuthProvider provider() { return provider; }
            @Override public RuntimeProbe probe() {
                return new RuntimeProbe(provider, true, true, "test", "ready", List.of("runtime-model"), Map.of());
            }
            @Override public Flux<RuntimeEvent> stream(RuntimeRequest request) {
                captured.set(request);
                Map<String, Object> metadata = Map.of(
                        "model", "runtime-model",
                        "responseId", "test-id",
                        "runtimeField", "runtime-value");
                return Flux.just(
                        RuntimeEvent.started(metadata),
                        RuntimeEvent.delta(
                                0, "SUB", Map.of("reasoningContent", "test-reasoning"), metadata),
                        RuntimeEvent.delta("AUTH_OK", metadata),
                        RuntimeEvent.completed(metadata,
                                new RuntimeUsage(4, 2, 6, 1L, 2L, Map.of()),
                                finishReason));
            }
        };
    }
}
