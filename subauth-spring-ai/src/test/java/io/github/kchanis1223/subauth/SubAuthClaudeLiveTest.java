package io.github.kchanis1223.subauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kchanis1223.subauth.runtime.RuntimeRegistry;
import io.github.kchanis1223.subauth.runtime.claude.ClaudeCodeRuntimeAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

@EnabledIfEnvironmentVariable(named = "SUBAUTH_LIVE_TESTS", matches = "true")
class SubAuthClaudeLiveTest {
    @Test
    void callsClaudeSubscriptionThroughSpringAiChatModel() {
        ClaudeCodeRuntimeAdapter adapter = new ClaudeCodeRuntimeAdapter(
                new ObjectMapper(), "claude", Duration.ofSeconds(20));
        try (RuntimeRegistry runtimes = new RuntimeRegistry(List.of(adapter))) {
            SubAuthChatModel model = new SubAuthChatModel(
                    runtimes,
                    SubAuthChatOptions.builder()
                            .provider(SubAuthProvider.CLAUDE)
                            .model("auto")
                            .effort(SubAuthEffort.MEDIUM)
                            .build(),
                    Duration.ofMinutes(2));

            ChatResponse response = model.call(new Prompt("Reply exactly: SUBAUTH_CLAUDE_SPRING_OK"));

            assertThat(response.getResult().getOutput().getText().trim())
                    .isEqualTo("SUBAUTH_CLAUDE_SPRING_OK");
            assertThat((Object) response.getMetadata().get("provider")).isEqualTo("claude");
            assertThat((Object) response.getMetadata().get("transport")).isEqualTo("subscription-runtime");
        }
    }
}
