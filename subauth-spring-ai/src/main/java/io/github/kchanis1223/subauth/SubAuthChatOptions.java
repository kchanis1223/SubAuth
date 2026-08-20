package io.github.kchanis1223.subauth;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

public final class SubAuthChatOptions implements ToolCallingChatOptions {
    private final SubAuthProvider provider;
    private final SubAuthEffort effort;
    private final String model;
    private final Double frequencyPenalty;
    private final Integer maxTokens;
    private final Double presencePenalty;
    private final List<String> stopSequences;
    private final Double temperature;
    private final Integer topK;
    private final Double topP;
    private List<ToolCallback> toolCallbacks;
    private Map<String, Object> toolContext;
    private Set<String> toolNames;
    private Boolean internalToolExecutionEnabled;

    private SubAuthChatOptions(Builder builder) {
        this.provider = builder.provider;
        this.effort = builder.effort;
        this.model = builder.model;
        this.frequencyPenalty = builder.frequencyPenalty;
        this.maxTokens = builder.maxTokens;
        this.presencePenalty = builder.presencePenalty;
        this.stopSequences = builder.stopSequences == null ? null : List.copyOf(builder.stopSequences);
        this.temperature = builder.temperature;
        this.topK = builder.topK;
        this.topP = builder.topP;
        this.toolCallbacks = List.copyOf(builder.toolCallbacks);
        this.toolContext = Map.copyOf(builder.toolContext);
        this.toolNames = Set.copyOf(builder.toolNames);
        this.internalToolExecutionEnabled = builder.internalToolExecutionEnabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    public SubAuthProvider getProvider() {
        return provider;
    }

    public SubAuthEffort getEffort() {
        return effort;
    }

    @Override public String getModel() { return model; }
    @Override public Double getFrequencyPenalty() { return frequencyPenalty; }
    @Override public Integer getMaxTokens() { return maxTokens; }
    @Override public Double getPresencePenalty() { return presencePenalty; }
    @Override public List<String> getStopSequences() { return stopSequences; }
    @Override public Double getTemperature() { return temperature; }
    @Override public Integer getTopK() { return topK; }
    @Override public Double getTopP() { return topP; }
    @Override public List<ToolCallback> getToolCallbacks() { return toolCallbacks; }
    @Override public Map<String, Object> getToolContext() { return toolContext; }

    /** Spring AI 1.1 compatibility method. */
    public void setToolCallbacks(List<ToolCallback> toolCallbacks) {
        this.toolCallbacks = toolCallbacks == null ? List.of() : List.copyOf(toolCallbacks);
    }

    /** Spring AI 1.1 compatibility method. */
    public Set<String> getToolNames() { return toolNames; }

    /** Spring AI 1.1 compatibility method. */
    public void setToolNames(Set<String> toolNames) {
        this.toolNames = toolNames == null ? Set.of() : Set.copyOf(toolNames);
    }

    /** Spring AI 1.1 compatibility method. */
    public Boolean getInternalToolExecutionEnabled() { return internalToolExecutionEnabled; }

    /** Spring AI 1.1 compatibility method. */
    public void setInternalToolExecutionEnabled(Boolean enabled) {
        this.internalToolExecutionEnabled = enabled;
    }

    /** Spring AI 1.1 compatibility method. */
    public void setToolContext(Map<String, Object> toolContext) {
        this.toolContext = toolContext == null ? Map.of() : Map.copyOf(toolContext);
    }

    @Override
    public Builder mutate() {
        return new Builder(this);
    }

    /**
     * Spring AI 1.1 uses {@code copy()} while Spring AI 2.0 uses
     * {@code mutate()}. Keeping both methods makes the options object binary
     * compatible with either contract.
     */
    public ChatOptions copy() {
        return new Builder(this).build();
    }

    public static final class Builder implements ToolCallingChatOptions.Builder<Builder> {
        private SubAuthProvider provider;
        private SubAuthEffort effort;
        private String model;
        private Double frequencyPenalty;
        private Integer maxTokens;
        private Double presencePenalty;
        private List<String> stopSequences;
        private Double temperature;
        private Integer topK;
        private Double topP;
        private List<ToolCallback> toolCallbacks = List.of();
        private Map<String, Object> toolContext = Map.of();
        private Set<String> toolNames = Set.of();
        private Boolean internalToolExecutionEnabled;

        public Builder() {}

        private Builder(SubAuthChatOptions source) {
            this.provider = source.provider;
            this.effort = source.effort;
            this.model = source.model;
            this.frequencyPenalty = source.frequencyPenalty;
            this.maxTokens = source.maxTokens;
            this.presencePenalty = source.presencePenalty;
            this.stopSequences = source.stopSequences;
            this.temperature = source.temperature;
            this.topK = source.topK;
            this.topP = source.topP;
            this.toolCallbacks = source.toolCallbacks;
            this.toolContext = source.toolContext;
            this.toolNames = source.toolNames;
            this.internalToolExecutionEnabled = source.internalToolExecutionEnabled;
        }

        public Builder provider(SubAuthProvider provider) { this.provider = provider; return this; }
        public Builder effort(SubAuthEffort effort) { this.effort = effort; return this; }
        @Override public Builder model(String model) { this.model = model; return this; }
        @Override public Builder frequencyPenalty(Double value) { this.frequencyPenalty = value; return this; }
        @Override public Builder maxTokens(Integer value) { this.maxTokens = value; return this; }
        @Override public Builder presencePenalty(Double value) { this.presencePenalty = value; return this; }
        @Override public Builder stopSequences(List<String> value) { this.stopSequences = value; return this; }
        @Override public Builder temperature(Double value) { this.temperature = value; return this; }
        @Override public Builder topK(Integer value) { this.topK = value; return this; }
        @Override public Builder topP(Double value) { this.topP = value; return this; }
        @Override public Builder toolCallbacks(List<ToolCallback> value) {
            this.toolCallbacks = value == null ? List.of() : List.copyOf(value);
            return this;
        }
        @Override public Builder toolCallbacks(ToolCallback... value) {
            return toolCallbacks(value == null ? List.of() : List.of(value));
        }
        @Override public Builder toolContext(Map<String, Object> value) {
            this.toolContext = value == null ? Map.of() : Map.copyOf(value);
            return this;
        }
        @Override public Builder toolContext(String key, Object value) {
            Map<String, Object> updated = new LinkedHashMap<>(toolContext);
            updated.put(key, value);
            this.toolContext = Map.copyOf(updated);
            return this;
        }

        /** Spring AI 1.1 compatibility method. */
        public Builder toolNames(Set<String> value) {
            this.toolNames = value == null ? Set.of() : Set.copyOf(value);
            return this;
        }

        /** Spring AI 1.1 compatibility method. */
        public Builder toolNames(String... value) {
            this.toolNames = value == null
                    ? Set.of()
                    : Set.copyOf(new LinkedHashSet<>(List.of(value)));
            return this;
        }

        /** Spring AI 1.1 compatibility method. */
        public Builder internalToolExecutionEnabled(Boolean value) {
            this.internalToolExecutionEnabled = value;
            return this;
        }

        @Override
        public Builder clone() {
            return build().mutate();
        }

        @Override
        public Builder combineWith(ChatOptions.Builder<?> other) {
            ChatOptions options = other.build();
            if (options.getModel() != null) model(options.getModel());
            if (options.getFrequencyPenalty() != null) frequencyPenalty(options.getFrequencyPenalty());
            if (options.getMaxTokens() != null) maxTokens(options.getMaxTokens());
            if (options.getPresencePenalty() != null) presencePenalty(options.getPresencePenalty());
            if (options.getStopSequences() != null) stopSequences(options.getStopSequences());
            if (options.getTemperature() != null) temperature(options.getTemperature());
            if (options.getTopK() != null) topK(options.getTopK());
            if (options.getTopP() != null) topP(options.getTopP());
            if (options instanceof ToolCallingChatOptions toolOptions) {
                if (toolOptions.getToolCallbacks() != null) {
                    toolCallbacks(toolOptions.getToolCallbacks());
                }
                if (toolOptions.getToolContext() != null) {
                    toolContext(toolOptions.getToolContext());
                }
            }
            if (options instanceof SubAuthChatOptions subAuth) {
                if (subAuth.getProvider() != null) provider(subAuth.getProvider());
                if (subAuth.getEffort() != null) effort(subAuth.getEffort());
                toolNames(subAuth.getToolNames());
                internalToolExecutionEnabled(subAuth.getInternalToolExecutionEnabled());
            }
            return this;
        }

        @Override
        public SubAuthChatOptions build() {
            return new SubAuthChatOptions(this);
        }
    }
}
