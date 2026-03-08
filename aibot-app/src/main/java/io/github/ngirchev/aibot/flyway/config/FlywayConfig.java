package io.github.ngirchev.aibot.flyway.config;

import org.springframework.context.annotation.Configuration;

/**
 * Flyway configuration for main application.
 * Now uses modular migrations from individual modules.
 *
 * Config moved to module configs:
 * - CoreFlywayConfig (aibot-common) - base migrations, always run
 * - TelegramFlywayConfig (aibot-telegram) - Telegram module migrations
 * - RestFlywayConfig (aibot-rest) - REST module migrations
 *
 * Each config creates Flyway bean with initMethod = "migrate",
 * migrations run automatically on Spring context init.
 *
 * FlywayMigrationCheck verifies all migrations status after app startup.
 */
@Configuration
public class FlywayConfig {
    // Migration config moved to module configs
    // This class kept for backward compatibility and documentation
} 