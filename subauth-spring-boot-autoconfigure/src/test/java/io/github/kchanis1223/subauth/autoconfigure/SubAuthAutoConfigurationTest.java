package io.github.kchanis1223.subauth.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kchanis1223.subauth.SubAuthChatModel;
import io.github.kchanis1223.subauth.SubAuthEffort;
import io.github.kchanis1223.subauth.SubAuthProvider;
import io.github.kchanis1223.subauth.SubAuthUnsupportedOptionsPolicy;
import io.github.kchanis1223.subauth.runtime.RuntimeRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

class SubAuthAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SubAuthAutoConfiguration.class));

    @Test
    void createsSpringAiChatModelWhenSelected() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.model.chat=subauth",
                        "spring.ai.subauth.provider=claude",
                        "spring.ai.subauth.model=sonnet",
                        "spring.ai.subauth.effort=high")
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatModel.class);
                    assertThat(context).hasSingleBean(SubAuthChatModel.class);
                    assertThat(context).hasSingleBean(RuntimeRegistry.class);
                    SubAuthProperties properties = context.getBean(SubAuthProperties.class);
                    assertThat(properties.getProvider()).isEqualTo(SubAuthProvider.CLAUDE);
                    assertThat(properties.getModel()).isEqualTo("sonnet");
                    assertThat(properties.getEffort()).isEqualTo(SubAuthEffort.HIGH);
                    assertThat(properties.getUnsupportedOptions())
                            .isEqualTo(SubAuthUnsupportedOptionsPolicy.IGNORE);
                });
    }

    @Test
    void bindsUnsupportedOptionsPolicy() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.model.chat=subauth",
                        "spring.ai.subauth.unsupported-options=reject")
                .run(context -> assertThat(context.getBean(SubAuthProperties.class)
                        .getUnsupportedOptions()).isEqualTo(SubAuthUnsupportedOptionsPolicy.REJECT));
    }

    @Test
    void doesNotActivateForAnotherSpringAiModel() {
        contextRunner.withPropertyValues("spring.ai.model.chat=openai")
                .run(context -> assertThat(context).doesNotHaveBean(SubAuthChatModel.class));
    }

    @Test
    void remainsThePrimaryModelWhenAnotherChatModelBeanExists() {
        contextRunner
                .withUserConfiguration(CompetingModelConfiguration.class)
                .withPropertyValues("spring.ai.model.chat=subauth")
                .run(context -> {
                    assertThat(context).hasBean("subAuthChatModel");
                    assertThat(context).hasBean("officialChatModel");
                    assertThat(context.getBeansOfType(ChatModel.class)).hasSize(2);
                    assertThat(context.getBean(ChatModel.class)).isInstanceOf(SubAuthChatModel.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CompetingModelConfiguration {
        @Bean
        ChatModel officialChatModel() {
            return fixedModel("OFFICIAL");
        }
    }

    private static ChatModel fixedModel(String text) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(java.util.List.of(
                        new Generation(new AssistantMessage(text))));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }
        };
    }
}
