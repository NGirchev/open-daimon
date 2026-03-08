package io.github.ngirchev.aibot.flyway;

import lombok.RequiredArgsConstructor;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Arrays;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FlywayMigrationCheck implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrationCheck.class);
    
    private final List<Flyway> flyways;
    
    @Override
    public void run(String... args) {
        log.info("Checking Flyway migrations for {} contexts...", flyways.size());
        for (Flyway flyway : flyways) {
            String locations = Arrays.stream(flyway.getConfiguration().getLocations())
                    .map(Object::toString)
                    .collect(Collectors.joining(","));
            MigrationInfo[] applied = flyway.info().applied();
            log.info("Context [{}]: {} applied migration(s)", locations, applied.length);
            for (MigrationInfo info : applied) {
                log.info(" -> {} | {} | {} | {}", 
                        info.getVersion(), info.getDescription(), info.getType(), info.getState());
            }
            if (applied.length == 0) {
                log.warn("Context [{}]: no migrations applied", locations);
            }
        }
    }
} 