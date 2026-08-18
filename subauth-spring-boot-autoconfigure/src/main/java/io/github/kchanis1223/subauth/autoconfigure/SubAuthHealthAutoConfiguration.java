package io.github.kchanis1223.subauth.autoconfigure;

import io.github.kchanis1223.subauth.runtime.RuntimeProbe;
import io.github.kchanis1223.subauth.runtime.RuntimeRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = SubAuthAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "spring.ai.model", name = "chat", havingValue = "subauth")
public class SubAuthHealthAutoConfiguration {

    @Bean
    public HealthIndicator subAuthHealthIndicator(
            RuntimeRegistry registry, SubAuthProperties properties) {
        return () -> {
            RuntimeProbe probe = registry.require(properties.getProvider()).probe();
            Health.Builder health = probe.subscriptionReady() ? Health.up() : Health.down();
            return health
                    .withDetail("provider", probe.provider().name().toLowerCase())
                    .withDetail("available", probe.available())
                    .withDetail("subscriptionReady", probe.subscriptionReady())
                    .withDetail("detail", probe.detail())
                    .withDetail("models", probe.models())
                    .build();
        };
    }
}
