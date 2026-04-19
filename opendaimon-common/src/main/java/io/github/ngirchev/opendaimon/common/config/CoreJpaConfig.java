package io.github.ngirchev.opendaimon.common.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA configuration for base module opendaimon-common.
 * Scans base entities (User, Message) and repositories.
 * This config is always active as it contains base models.
 */
@Configuration
@EntityScan(basePackages = {
        "io.github.ngirchev.opendaimon.common.model",
        "io.github.ngirchev.opendaimon.common.agent.persistence"
})
@EnableJpaRepositories(basePackages = {
        "io.github.ngirchev.opendaimon.common.repository",
        "io.github.ngirchev.opendaimon.common.agent.persistence"
})
public class CoreJpaConfig {
    // JPA config for base entities and repositories
}

