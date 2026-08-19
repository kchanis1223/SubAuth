package io.github.kchanis1223.subauth.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kchanis1223.subauth.SubAuthEffort;
import io.github.kchanis1223.subauth.SubAuthProvider;
import io.github.kchanis1223.subauth.SubAuthUnsupportedCapabilityException;
import io.github.kchanis1223.subauth.runtime.claude.ClaudeCodeRuntimeAdapter;
import io.github.kchanis1223.subauth.runtime.gemini.AntigravityRuntimeAdapter;
import io.github.kchanis1223.subauth.runtime.openai.CodexRuntimeAdapter;
import org.junit.jupiter.api.Test;

class RuntimeCapabilitiesTest {
    @Test
    void providerAdaptersDeclareTheirEffortLimits() {
        ObjectMapper objectMapper = new ObjectMapper();
        RuntimeCapabilities openAi = new CodexRuntimeAdapter(
                objectMapper, "codex", Duration.ofSeconds(1)).capabilities();
        RuntimeCapabilities claude = new ClaudeCodeRuntimeAdapter(
                objectMapper, "claude", Duration.ofSeconds(1)).capabilities();
        RuntimeCapabilities gemini = new AntigravityRuntimeAdapter(
                objectMapper, "agy", Duration.ofSeconds(1)).capabilities();

        assertThat(openAi.efforts()).containsExactlyInAnyOrder(SubAuthEffort.values());
        assertThat(claude.efforts()).contains(
                SubAuthEffort.LOW, SubAuthEffort.MEDIUM, SubAuthEffort.HIGH,
                SubAuthEffort.XHIGH, SubAuthEffort.MAX)
                .doesNotContain(SubAuthEffort.MINIMAL);
        assertThat(gemini.efforts()).containsExactlyInAnyOrder(
                SubAuthEffort.LOW, SubAuthEffort.MEDIUM, SubAuthEffort.HIGH);
        assertThat(openAi.media()).isTrue();
        assertThat(claude.toolCalls()).isFalse();
        assertThat(gemini.toolResults()).isFalse();
    }

    @Test
    void rejectsAnEffortOutsideTheProviderCapabilitySet() {
        RuntimeCapabilities capabilities = RuntimeCapabilities.textOnly(Set.of(
                SubAuthEffort.LOW, SubAuthEffort.MEDIUM, SubAuthEffort.HIGH));
        RuntimeRequest request = new RuntimeRequest(
                SubAuthProvider.GEMINI,
                List.of(RuntimeMessage.text(RuntimeRole.USER, "hello", Map.of())),
                null,
                SubAuthEffort.XHIGH,
                Duration.ofSeconds(5));

        assertThatThrownBy(() -> capabilities.validate(request))
                .isInstanceOf(SubAuthUnsupportedCapabilityException.class)
                .hasMessageContaining("effort=xhigh");
    }

    @Test
    void rejectsStructuredContentThatTheRuntimeCannotCarry() {
        RuntimeCapabilities capabilities = RuntimeCapabilities.textOnly(Set.of(SubAuthEffort.MEDIUM));
        RuntimeMessage message = new RuntimeMessage(
                RuntimeRole.USER,
                List.of(new RuntimeContent.Media("image/png", new byte[] { 1 }, null, null)),
                Map.of());
        RuntimeRequest request = new RuntimeRequest(
                SubAuthProvider.OPENAI,
                List.of(message),
                null,
                SubAuthEffort.MEDIUM,
                Duration.ofSeconds(5));

        assertThatThrownBy(() -> capabilities.validate(request))
                .isInstanceOf(SubAuthUnsupportedCapabilityException.class)
                .hasMessageContaining("media content");
    }
}
