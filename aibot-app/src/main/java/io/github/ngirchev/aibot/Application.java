package io.github.ngirchev.aibot;

import io.github.ngirchev.dotenv.DotEnvLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import io.github.ngirchev.aibot.flyway.FlywayMigrationCheck;
import io.github.ngirchev.aibot.flyway.config.FlywayConfig;

/**
 * Main AI Bot Router application.
 * JPA configs are in module configs:
 * - CommonJpaConfig (aibot-common) - base Entity and repositories
 * - TelegramJpaConfig (aibot-telegram) - Telegram Entity and repositories (conditional)
 * - RestJpaConfig (aibot-rest) - REST Entity and repositories (conditional)
 */
@Slf4j
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = JpaRepositoriesAutoConfiguration.class)
@EnableScheduling
@Import({
        FlywayConfig.class,
        FlywayMigrationCheck.class
})
public class Application {

    public static void main(String[] args) {
        DotEnvLoader.loadDotEnv();
        // Disable Reactor detailed stacktraces to reduce log size
        System.setProperty("reactor.trace.operatorStacktrace", "false");
        SpringApplication.run(Application.class, args);
    }
}