package io.github.kchanis1223.subauth.autoconfigure;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kchanis1223.subauth.SubAuthChatModel;
import io.github.kchanis1223.subauth.SubAuthChatOptions;
import io.github.kchanis1223.subauth.runtime.RuntimeAdapter;
import io.github.kchanis1223.subauth.runtime.RuntimeRegistry;
import io.github.kchanis1223.subauth.runtime.claude.ClaudeCodeRuntimeAdapter;
import io.github.kchanis1223.subauth.runtime.gemini.AntigravityRuntimeAdapter;
import io.github.kchanis1223.subauth.runtime.openai.CodexRuntimeAdapter;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.ObjectProvider;

@AutoConfiguration(beforeName = "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration")
@ConditionalOnClass(ChatModel.class)
@ConditionalOnProperty(prefix = "spring.ai.model", name = "chat", havingValue = "subauth")
@EnableConfigurationProperties(SubAuthProperties.class)
public class SubAuthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CodexRuntimeAdapter codexRuntimeAdapter(
            ObjectProvider<ObjectMapper> objectMappers, SubAuthProperties properties) {
        return new CodexRuntimeAdapter(
                objectMappers.getIfAvailable(ObjectMapper::new),
                properties.getCommands().getCodex(), properties.getProbeTimeout());
    }

    @Bean
    @ConditionalOnMissingBean
    public ClaudeCodeRuntimeAdapter claudeCodeRuntimeAdapter(
            ObjectProvider<ObjectMapper> objectMappers, SubAuthProperties properties) {
        return new ClaudeCodeRuntimeAdapter(
                objectMappers.getIfAvailable(ObjectMapper::new),
                properties.getCommands().getClaude(), properties.getProbeTimeout());
    }

    @Bean
    @ConditionalOnMissingBean
    public AntigravityRuntimeAdapter antigravityRuntimeAdapter(
            ObjectProvider<ObjectMapper> objectMappers, SubAuthProperties properties) {
        return new AntigravityRuntimeAdapter(
                objectMappers.getIfAvailable(ObjectMapper::new),
                properties.getCommands().getGemini(), properties.getProbeTimeout());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public RuntimeRegistry subAuthRuntimeRegistry(List<RuntimeAdapter> adapters) {
        return new RuntimeRegistry(adapters);
    }

    @Bean(name = "subAuthChatModel")
    @Primary
    @ConditionalOnMissingBean(SubAuthChatModel.class)
    public SubAuthChatModel subAuthChatModel(
            RuntimeRegistry registry, SubAuthProperties properties) {
        SubAuthChatOptions options = SubAuthChatOptions.builder()
                .provider(properties.getProvider())
                .model(properties.getModel())
                .effort(properties.getEffort())
                .build();
        return new SubAuthChatModel(registry, options, properties.getRequestTimeout());
    }
}
