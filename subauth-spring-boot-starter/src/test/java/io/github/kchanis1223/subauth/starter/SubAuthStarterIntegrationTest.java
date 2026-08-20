package io.github.kchanis1223.subauth.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.github.kchanis1223.subauth.SubAuthChatModel;
import io.github.kchanis1223.subauth.SubAuthEffort;
import io.github.kchanis1223.subauth.SubAuthProvider;
import io.github.kchanis1223.subauth.autoconfigure.SubAuthAutoConfiguration;
import io.github.kchanis1223.subauth.runtime.RuntimeAdapter;
import io.github.kchanis1223.subauth.runtime.RuntimeCapabilities;
import io.github.kchanis1223.subauth.runtime.RuntimeEvent;
import io.github.kchanis1223.subauth.runtime.RuntimeOption;
import io.github.kchanis1223.subauth.runtime.RuntimeProbe;
import io.github.kchanis1223.subauth.runtime.RuntimeRegistry;
import io.github.kchanis1223.subauth.runtime.RuntimeRequest;
import io.github.kchanis1223.subauth.runtime.RuntimeUsage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;

class SubAuthStarterIntegrationTest {
    @Test
    void starterProvidesNormalSpringAiChatClientBuilder() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        SubAuthAutoConfiguration.class,
                        ChatClientAutoConfiguration.class))
                .withPropertyValues("spring.ai.model.chat=subauth")
                .run(context -> {
                    assertThat(context).hasSingleBean(SubAuthChatModel.class);
                    assertThat(context).hasSingleBean(ChatClient.Builder.class);
                });
    }

    @Test
    void springAi20ChatClientToolsReachSubAuthRuntime() {
        CapturingRuntimeAdapter adapter = new CapturingRuntimeAdapter();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        SubAuthAutoConfiguration.class,
                        ChatClientAutoConfiguration.class))
                .withBean(RuntimeRegistry.class, () -> new RuntimeRegistry(List.of(adapter)))
                .withPropertyValues(
                        "spring.ai.model.chat=subauth",
                        "spring.ai.subauth.provider=openai")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(ChatClient.Builder.class)
                            .build()
                            .prompt()
                            .user("read status")
                            .tools(new CompatibilityTools())
                            .call()
                            .content();

                    assertThat(adapter.lastRequest.get().tools())
                            .singleElement()
                            .satisfies(tool -> assertThat(tool.name()).isEqualTo("readStatus"));
                });
    }

    static final class CapturingRuntimeAdapter implements RuntimeAdapter {
        private final AtomicReference<RuntimeRequest> lastRequest = new AtomicReference<>();

        @Override public SubAuthProvider provider() { return SubAuthProvider.OPENAI; }

        @Override
        public RuntimeCapabilities capabilities() {
            return new RuntimeCapabilities(
                    true, true, true, false, false, false,
                    Set.of(SubAuthEffort.values()),
                    Set.of(RuntimeOption.MODEL, RuntimeOption.EFFORT, RuntimeOption.TOOL_CALLBACKS));
        }

        @Override
        public RuntimeProbe probe() {
            return new RuntimeProbe(
                    provider(), true, true, "test", "ready",
                    List.of("compat-model"), Map.of());
        }

        @Override
        public Flux<RuntimeEvent> stream(RuntimeRequest request) {
            lastRequest.set(request);
            Map<String, Object> metadata = Map.of("model", "compat-model");
            return Flux.just(
                    RuntimeEvent.started(metadata),
                    RuntimeEvent.delta("SPRING_AI_2_0_OK", metadata),
                    RuntimeEvent.completed(
                            metadata,
                            new RuntimeUsage(1, 1, 2, null, null, Map.of()),
                            "stop"));
        }
    }

    static final class CompatibilityTools {
        @Tool(description = "Read compatibility status")
        String readStatus() {
            return "SPRING_AI_2_0_TOOL_OK";
        }
    }
}
