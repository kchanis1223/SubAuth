package io.github.kchanis1223.subauth.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kchanis1223.subauth.SubAuthChatModel;
import io.github.kchanis1223.subauth.SubAuthEffort;
import io.github.kchanis1223.subauth.SubAuthProvider;
import io.github.kchanis1223.subauth.runtime.RuntimeRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
                });
    }

    @Test
    void doesNotActivateForAnotherSpringAiModel() {
        contextRunner.withPropertyValues("spring.ai.model.chat=openai")
                .run(context -> assertThat(context).doesNotHaveBean(SubAuthChatModel.class));
    }
}
