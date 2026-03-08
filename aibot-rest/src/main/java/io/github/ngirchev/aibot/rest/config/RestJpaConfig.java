package io.github.ngirchev.aibot.rest.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA configuration for REST module.
 * Scans REST Entity and repositories.
 * Active only when REST module is enabled (ai-bot.rest.enabled=true).
 */
@EntityScan(basePackages = {
        "io.github.ngirchev.aibot.rest.model"
})
@EnableJpaRepositories(basePackages = {
        "io.github.ngirchev.aibot.rest.repository"
})
public class RestJpaConfig {
    // JPA config for REST Entity and repositories
}

