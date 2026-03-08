package io.github.ngirchev.aibot.common.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Flyway configuration for base migrations (core module).
 * These migrations run always as they define base tables.
 */
@Configuration
public class CoreFlywayConfig {

    private final DataSource dataSource;

    public CoreFlywayConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Bean(value = "coreFlyway", initMethod = "migrate")
    public Flyway coreFlyway() {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/core")
                .table("flyway_schema_history_core")
                .baselineOnMigrate(true)
                .validateOnMigrate(true)
                .load();
    }
}

