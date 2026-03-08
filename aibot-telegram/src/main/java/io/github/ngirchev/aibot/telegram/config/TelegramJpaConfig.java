package io.github.ngirchev.aibot.telegram.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA configuration for Telegram module.
 * Scans Telegram Entity and repositories.
 * Active only when Telegram module is enabled (ai-bot.telegram.enabled=true).
 */
@Configuration
@EntityScan(basePackages = {
        "io.github.ngirchev.aibot.telegram.model"
})
@EnableJpaRepositories(basePackages = {
        "io.github.ngirchev.aibot.telegram.repository"
})
@ConditionalOnProperty(name = "ai-bot.telegram.enabled", havingValue = "true", matchIfMissing = true)
public class TelegramJpaConfig {
    // JPA config for Telegram Entity and repositories
}

