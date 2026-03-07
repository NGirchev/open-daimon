package ru.girchev.aibot;

import org.springframework.boot.SpringBootConfiguration;

/**
 * Minimal configuration for @SpringBootConfiguration when scanning packages
 * from repository tests (ru.girchev.aibot.rest.repository, ru.girchev.aibot.telegram.repository).
 * Without this class slice tests (@DataJpaTest) fail with
 * "Unable to find a @SpringBootConfiguration".
 */
@SpringBootConfiguration
public class RepositoryTestConfig {
}
