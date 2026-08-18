package io.github.kchanis1223.subauth.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import io.github.kchanis1223.subauth.SubAuthChatModel;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

class ChatModelProfileSwitchIntegrationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SubAuthAutoConfiguration.class,
                    ChatClientAutoConfiguration.class))
            .withUserConfiguration(PortableServiceConfiguration.class);

    @Test
    void developmentProfileUsesSubAuthWithoutChangingApplicationCode() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.model.chat=subauth",
                        "spring.ai.subauth.provider=openai")
                .run(context -> {
                    assertThat(context.getBean(ChatModel.class)).isInstanceOf(SubAuthChatModel.class);
                    assertThat(context.getBean(PortableAiService.class).ask()).isEqualTo("SUBAUTH_PROFILE");
                });
    }

    @Test
    void productionProfileUsesTheOfficialModelWithoutChangingApplicationCode() {
        contextRunner
                .withPropertyValues("spring.ai.model.chat=openai")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SubAuthChatModel.class);
                    assertThat(context.getBean(ChatModel.class))
                            .isSameAs(context.getBean("officialChatModel"));
                    assertThat(context.getBean(PortableAiService.class).ask()).isEqualTo("OFFICIAL_PROFILE");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class PortableServiceConfiguration {
        @Bean(destroyMethod = "close")
        RuntimeRegistry testRuntimeRegistry() {
            RuntimeAdapter adapter = new RuntimeAdapter() {
                @Override public SubAuthProvider provider() { return SubAuthProvider.OPENAI; }

                @Override
                public RuntimeProbe probe() {
                    return new RuntimeProbe(
                            provider(), true, true, "test", "ready", List.of("test-model"), Map.of());
                }

                @Override
                public Flux<RuntimeEvent> stream(RuntimeRequest request) {
                    Map<String, Object> metadata = Map.of(
                            "model", "test-model", "responseId", "subauth-profile");
                    return Flux.just(
                            RuntimeEvent.started(metadata),
                            RuntimeEvent.delta("SUBAUTH_PROFILE", metadata),
                            RuntimeEvent.completed(
                                    metadata, new RuntimeUsage(1, 1, 2, null, null, Map.of()), "stop"));
                }
            };
            return new RuntimeRegistry(List.of(adapter));
        }

        @Bean
        ChatModel officialChatModel() {
            return fixedModel("OFFICIAL_PROFILE");
        }

        @Bean
        PortableAiService portableAiService(ChatClient.Builder builder) {
            return new PortableAiService(builder.build());
        }
    }

    static final class PortableAiService {
        private final ChatClient chatClient;

        PortableAiService(ChatClient chatClient) {
            this.chatClient = chatClient;
        }

        String ask() {
            return chatClient.prompt().user("profile switch").call().content();
        }
    }

    private static ChatModel fixedModel(String text) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }
        };
    }
}
