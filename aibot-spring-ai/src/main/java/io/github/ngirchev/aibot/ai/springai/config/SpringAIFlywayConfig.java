package io.github.ngirchev.aibot.ai.springai.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Flyway configuration for the Spring AI module.
 * Migrations run only when the Spring AI module is enabled.
 */
@Configuration
@DependsOn("coreFlyway")
@ConditionalOnProperty(name = "ai-bot.ai.spring-ai.enabled", havingValue = "true")
public class SpringAIFlywayConfig {

    private final DataSource dataSource;

    public SpringAIFlywayConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Bean(value = "springAiFlyway", initMethod = "migrate")
    public Flyway springAiFlyway() {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/springai")
                .table("flyway_schema_history_springai")
                .baselineVersion("0")
                .baselineOnMigrate(true)
                .validateOnMigrate(true)
                .load();
    }
}

