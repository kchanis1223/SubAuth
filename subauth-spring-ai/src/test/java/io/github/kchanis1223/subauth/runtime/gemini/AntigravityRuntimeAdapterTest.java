package io.github.kchanis1223.subauth.runtime.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import io.github.kchanis1223.subauth.SubAuthEffort;
import io.github.kchanis1223.subauth.SubAuthException;
import org.junit.jupiter.api.Test;

class AntigravityRuntimeAdapterTest {
    private static final List<String> MODELS = List.of(
            "gemini-3.7-flash-high",
            "gemini-3.7-flash-medium",
            "gemini-3.7-flash-low",
            "claude-sonnet-4-6");

    @Test
    void autoSelectsTheFirstModelCompatibleWithEffort() {
        assertThat(AntigravityRuntimeAdapter.selectModel(MODELS, null, SubAuthEffort.MEDIUM))
                .isEqualTo("gemini-3.7-flash-medium");
    }

    @Test
    void autoWithoutEffortSelectsTheFirstGeminiModel() {
        assertThat(AntigravityRuntimeAdapter.selectModel(MODELS, null, null))
                .isEqualTo("gemini-3.7-flash-high");
    }

    @Test
    void rejectsAnExplicitModelThatConflictsWithEffort() {
        assertThatThrownBy(() -> AntigravityRuntimeAdapter.selectModel(
                MODELS, "gemini-3.7-flash-high", SubAuthEffort.MEDIUM))
                .isInstanceOf(SubAuthException.class)
                .hasMessageContaining("conflicts with effort=medium");
    }
}
