package io.github.kchanis1223.subauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kchanis1223.subauth.runtime.RuntimeRegistry;
import io.github.kchanis1223.subauth.runtime.gemini.AntigravityRuntimeAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

@EnabledIfEnvironmentVariable(named = "SUBAUTH_LIVE_TESTS", matches = "true")
class SubAuthGeminiLiveTest {
    @Test
    void callsGeminiSubscriptionThroughSpringAiChatModel() {
        AntigravityRuntimeAdapter adapter = new AntigravityRuntimeAdapter(
                new ObjectMapper(), "agy", Duration.ofSeconds(20));
        try (RuntimeRegistry runtimes = new RuntimeRegistry(List.of(adapter))) {
            SubAuthChatModel model = new SubAuthChatModel(
                    runtimes,
                    SubAuthChatOptions.builder()
                            .provider(SubAuthProvider.GEMINI)
                            .model("auto")
                            .effort(SubAuthEffort.MEDIUM)
                            .build(),
                    Duration.ofMinutes(5));

            ChatResponse response = model.call(new Prompt("Reply exactly: SUBAUTH_GEMINI_SPRING_OK"));

            assertThat(response.getResult().getOutput().getText().trim())
                    .isEqualTo("SUBAUTH_GEMINI_SPRING_OK");
            assertThat((Object) response.getMetadata().get("provider")).isEqualTo("gemini");
            assertThat((Object) response.getMetadata().get("transport")).isEqualTo("subscription-runtime");
        }
    }
}
