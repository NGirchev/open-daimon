package io.github.ngirchev.aibot.bulkhead.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import io.github.ngirchev.aibot.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.aibot.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.aibot.bulkhead.service.IUserService;
import io.github.ngirchev.aibot.bulkhead.service.IWhitelistService;
import io.github.ngirchev.aibot.bulkhead.service.impl.DefaultUserPriorityService;

@AutoConfiguration
@EnableConfigurationProperties(BulkHeadProperties.class)
@ConditionalOnProperty(name = "ai-bot.common.bulkhead.enabled", havingValue = "true")
public class BulkHeadAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public IUserPriorityService userPriorityService(
            IUserService userService,
            IWhitelistService whitelistService) {
        return new DefaultUserPriorityService(userService, whitelistService);
    }

    @Bean
    @ConditionalOnMissingBean
    public PriorityRequestExecutor priorityRequestExecutor(
            IUserPriorityService userPriorityService,
            BulkHeadProperties bulkHeadProperties) {
        return new PriorityRequestExecutor(userPriorityService, bulkHeadProperties);
    }
}
