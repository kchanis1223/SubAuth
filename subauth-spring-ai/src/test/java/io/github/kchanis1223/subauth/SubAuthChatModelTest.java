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
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

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
                new UserMessage("current"))));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("SUBAUTH_OK");
        assertThat(response.getMetadata().getModel()).isEqualTo("runtime-model");
        assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(4);
        assertThat(captured.get().messages())
                .extracting(message -> message.role() + ":" + message.text())
                .containsExactly("system:Be exact", "user:first", "assistant:previous", "user:current");
    }

    @Test
    void streamUsesSpringAiFluxResponses() {
        SubAuthChatModel model = model(runtime(new AtomicReference<>()));

        StepVerifier.create(model.stream(new Prompt("hello"))
                        .map(response -> response.getResult().getOutput().getText()))
                .expectNext("SUB", "AUTH_OK", "")
                .verifyComplete();
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
    void unsupportedPortableOptionsFailExplicitly() {
        SubAuthChatModel model = model(runtime(new AtomicReference<>()));
        ChatOptions options = ChatOptions.builder().temperature(0.3).build();

        assertThatThrownBy(() -> model.call(new Prompt("hello", options)))
                .isInstanceOf(SubAuthUnsupportedCapabilityException.class)
                .hasMessageContaining("provider, model, and effort");
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
        return new RuntimeAdapter() {
            @Override public SubAuthProvider provider() { return provider; }
            @Override public RuntimeProbe probe() {
                return new RuntimeProbe(provider, true, true, "test", "ready", List.of("runtime-model"), Map.of());
            }
            @Override public Flux<RuntimeEvent> stream(RuntimeRequest request) {
                captured.set(request);
                Map<String, Object> metadata = Map.of("model", "runtime-model", "responseId", "test-id");
                return Flux.just(
                        RuntimeEvent.started(metadata),
                        RuntimeEvent.delta("SUB", metadata),
                        RuntimeEvent.delta("AUTH_OK", metadata),
                        RuntimeEvent.completed(metadata,
                                new RuntimeUsage(4, 2, 6, null, null, Map.of())));
            }
        };
    }
}
