package io.github.kchanis1223.subauth.autoconfigure;

import java.time.Duration;

import io.github.kchanis1223.subauth.SubAuthEffort;
import io.github.kchanis1223.subauth.SubAuthProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("spring.ai.subauth")
public class SubAuthProperties {
    private SubAuthProvider provider = SubAuthProvider.OPENAI;
    private String model = "auto";
    private SubAuthEffort effort = SubAuthEffort.MEDIUM;
    private Duration requestTimeout = Duration.ofMinutes(5);
    private Duration probeTimeout = Duration.ofSeconds(20);
    private final Commands commands = new Commands();

    public SubAuthProvider getProvider() { return provider; }
    public void setProvider(SubAuthProvider provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public SubAuthEffort getEffort() { return effort; }
    public void setEffort(SubAuthEffort effort) { this.effort = effort; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public Duration getProbeTimeout() { return probeTimeout; }
    public void setProbeTimeout(Duration probeTimeout) { this.probeTimeout = probeTimeout; }
    public Commands getCommands() { return commands; }

    public static class Commands {
        private String codex = "codex";
        private String claude = "claude";
        private String gemini = "agy";

        public String getCodex() { return codex; }
        public void setCodex(String codex) { this.codex = codex; }
        public String getClaude() { return claude; }
        public void setClaude(String claude) { this.claude = claude; }
        public String getGemini() { return gemini; }
        public void setGemini(String gemini) { this.gemini = gemini; }
    }
}
