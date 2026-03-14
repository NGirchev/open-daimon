package io.github.ngirchev.opendaimon.rest.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Flyway configuration for REST module.
 * Migrations run only when REST module is enabled.
 */
@Configuration
@DependsOn("coreFlyway")
public class RestFlywayConfig {

    private final DataSource dataSource;

    public RestFlywayConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Bean(value = "restFlyway", initMethod = "migrate")
    public Flyway restFlyway() {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/rest")
                .table("flyway_schema_history_rest")
                .baselineVersion("0")
                .baselineOnMigrate(true)
                .validateOnMigrate(true)
                .load();
    }
}

