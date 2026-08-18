package io.github.kchanis1223.subauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SubAuthChatOptionsTest {
    @Test
    void mutatePreservesSubAuthSpecificOptions() {
        SubAuthChatOptions options = SubAuthChatOptions.builder()
                .provider(SubAuthProvider.GEMINI)
                .model("gemini-test")
                .effort(SubAuthEffort.HIGH)
                .build();

        SubAuthChatOptions changed = options.mutate().model("gemini-next").build();

        assertThat(changed.getProvider()).isEqualTo(SubAuthProvider.GEMINI);
        assertThat(changed.getEffort()).isEqualTo(SubAuthEffort.HIGH);
        assertThat(changed.getModel()).isEqualTo("gemini-next");
    }
}
