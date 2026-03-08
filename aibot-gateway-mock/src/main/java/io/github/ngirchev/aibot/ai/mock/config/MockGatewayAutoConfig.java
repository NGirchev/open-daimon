package io.github.ngirchev.aibot.ai.mock.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import io.github.ngirchev.aibot.ai.mock.service.MockGateway;
import io.github.ngirchev.aibot.common.service.AIGatewayRegistry;

@AutoConfiguration
@ConditionalOnProperty(name = "ai-bot.ai.gateway-mock.enabled", havingValue = "true")
@EnableConfigurationProperties(MockGatewayProperties.class)
public class MockGatewayAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public MockGateway mockGateway(
            AIGatewayRegistry aiGatewayRegistry,
            MockGatewayProperties properties
    ) {
        return new MockGateway(aiGatewayRegistry);
    }
}
