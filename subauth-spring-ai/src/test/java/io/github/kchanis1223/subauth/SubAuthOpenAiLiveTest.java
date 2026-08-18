package io.github.kchanis1223.subauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kchanis1223.subauth.runtime.RuntimeRegistry;
import io.github.kchanis1223.subauth.runtime.openai.CodexRuntimeAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.prompt.Prompt;

@EnabledIfEnvironmentVariable(named = "SUBAUTH_LIVE_TESTS", matches = "true")
class SubAuthOpenAiLiveTest {
    @Test
    void callsChatGptSubscriptionThroughSpringAiChatModel() {
        CodexRuntimeAdapter adapter = new CodexRuntimeAdapter(
                new ObjectMapper(), "codex", Duration.ofSeconds(20));
        try (RuntimeRegistry runtimes = new RuntimeRegistry(List.of(adapter))) {
            SubAuthChatModel model = new SubAuthChatModel(
                    runtimes,
                    SubAuthChatOptions.builder()
                            .provider(SubAuthProvider.OPENAI)
                            .model("auto")
                            .effort(SubAuthEffort.MEDIUM)
                            .build(),
                    Duration.ofMinutes(2));

            String text = model.call(new Prompt("Reply exactly: SUBAUTH_SPRING_OK"))
                    .getResult().getOutput().getText();

            assertThat(text.trim()).isEqualTo("SUBAUTH_SPRING_OK");
        }
    }
}
