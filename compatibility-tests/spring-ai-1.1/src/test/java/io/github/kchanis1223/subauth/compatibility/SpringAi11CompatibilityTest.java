package io.github.kchanis1223.subauth.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

class SpringAi11CompatibilityTest {

    @Test
    void springAi11ChatClientUsesSubAuthWithoutProviderApiCode() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        SubAuthAutoConfiguration.class,
                        ChatClientAutoConfiguration.class))
                .withUserConfiguration(TestRuntimeConfiguration.class)
                .withPropertyValues(
                        "spring.ai.model.chat=subauth",
                        "spring.ai.subauth.provider=openai")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    String content = context.getBean(ChatClient.Builder.class)
                            .build()
                            .prompt()
                            .user("compatibility")
                            .call()
                            .content();
                    assertThat(content).isEqualTo("SPRING_AI_1_1_OK");
                });
    }

    @Test
    void springAi11ChatClientToolsReachSubAuthRuntime() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        SubAuthAutoConfiguration.class,
                        ChatClientAutoConfiguration.class))
                .withUserConfiguration(TestRuntimeConfiguration.class)
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

                    RuntimeRequest request = context.getBean(CapturingRuntimeAdapter.class)
                            .lastRequest.get();
                    assertThat(request).isNotNull();
                    assertThat(request.tools()).singleElement().satisfies(tool -> {
                        assertThat(tool.name()).isEqualTo("readStatus");
                        assertThat(tool.execute("{}"))
                                .contains("SPRING_AI_1_1_TOOL_OK");
                    });
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestRuntimeConfiguration {
        @Bean(destroyMethod = "close")
        RuntimeRegistry testRuntimeRegistry(CapturingRuntimeAdapter adapter) {
            return new RuntimeRegistry(List.of(adapter));
        }

        @Bean
        CapturingRuntimeAdapter capturingRuntimeAdapter() {
            return new CapturingRuntimeAdapter();
        }
    }

    static final class CapturingRuntimeAdapter implements RuntimeAdapter {
        private final AtomicReference<RuntimeRequest> lastRequest = new AtomicReference<>();

        @Override
        public SubAuthProvider provider() {
            return SubAuthProvider.OPENAI;
        }

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
            Map<String, Object> metadata = Map.of(
                    "model", "compat-model",
                    "responseId", "spring-ai-1.1");
            return Flux.just(
                    RuntimeEvent.started(metadata),
                    RuntimeEvent.delta("SPRING_AI_1_1_OK", metadata),
                    RuntimeEvent.completed(
                            metadata,
                            new RuntimeUsage(3, 2, 5, 1L, 1L, Map.of()),
                            "stop"));
        }
    }

    static final class CompatibilityTools {
        @Tool(description = "Read compatibility status")
        String readStatus() {
            return "SPRING_AI_1_1_TOOL_OK";
        }
    }
}
