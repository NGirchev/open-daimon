package io.github.ngirchev.opendaimon.telegram.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA configuration for Telegram module.
 * Scans Telegram Entity and repositories.
 * Active only when Telegram module is enabled (open-daimon.telegram.enabled=true).
 */
@Configuration
@EntityScan(basePackages = {
        "io.github.ngirchev.opendaimon.telegram.model"
})
@EnableJpaRepositories(basePackages = {
        "io.github.ngirchev.opendaimon.telegram.repository"
})
@ConditionalOnProperty(name = "open-daimon.telegram.enabled", havingValue = "true", matchIfMissing = true)
public class TelegramJpaConfig {
    // JPA config for Telegram Entity and repositories
}

