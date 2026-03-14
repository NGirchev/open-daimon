package io.github.ngirchev.opendaimon.rest.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA configuration for REST module.
 * Scans REST Entity and repositories.
 * Active only when REST module is enabled (open-daimon.rest.enabled=true).
 */
@EntityScan(basePackages = {
        "io.github.ngirchev.opendaimon.rest.model"
})
@EnableJpaRepositories(basePackages = {
        "io.github.ngirchev.opendaimon.rest.repository"
})
public class RestJpaConfig {
    // JPA config for REST Entity and repositories
}

