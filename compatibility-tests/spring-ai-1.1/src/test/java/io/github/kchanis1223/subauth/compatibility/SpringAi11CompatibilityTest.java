package io.github.kchanis1223.subauth.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import io.github.kchanis1223.subauth.SubAuthProvider;
import io.github.kchanis1223.subauth.autoconfigure.SubAuthAutoConfiguration;
import io.github.kchanis1223.subauth.runtime.RuntimeAdapter;
import io.github.kchanis1223.subauth.runtime.RuntimeEvent;
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

    @Configuration(proxyBeanMethods = false)
    static class TestRuntimeConfiguration {
        @Bean(destroyMethod = "close")
        RuntimeRegistry testRuntimeRegistry() {
            RuntimeAdapter adapter = new RuntimeAdapter() {
                @Override
                public SubAuthProvider provider() {
                    return SubAuthProvider.OPENAI;
                }

                @Override
                public RuntimeProbe probe() {
                    return new RuntimeProbe(
                            provider(), true, true, "test", "ready",
                            List.of("compat-model"), Map.of());
                }

                @Override
                public Flux<RuntimeEvent> stream(RuntimeRequest request) {
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
            };
            return new RuntimeRegistry(List.of(adapter));
        }
    }
}
