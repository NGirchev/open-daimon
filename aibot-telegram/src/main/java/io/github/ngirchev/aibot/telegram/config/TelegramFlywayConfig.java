package io.github.ngirchev.aibot.telegram.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Flyway configuration for Telegram module.
 * Migrations run only when Telegram module is enabled.
 */
@Configuration
@DependsOn("coreFlyway")
public class TelegramFlywayConfig {

    private final DataSource dataSource;

    public TelegramFlywayConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Bean(value = "telegramFlyway", initMethod = "migrate")
    public Flyway telegramFlyway() {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/telegram")
                .table("flyway_schema_history_telegram")
                .baselineVersion("0")
                .baselineOnMigrate(true)
                .validateOnMigrate(true)
                .load();
    }
}

