package io.github.kchanis1223.subauth.starter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kchanis1223.subauth.SubAuthChatModel;
import io.github.kchanis1223.subauth.autoconfigure.SubAuthAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
}
