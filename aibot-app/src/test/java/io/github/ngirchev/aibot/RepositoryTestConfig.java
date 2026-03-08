package io.github.ngirchev.aibot;

import org.springframework.boot.SpringBootConfiguration;

/**
 * Minimal configuration for @SpringBootConfiguration when scanning packages
 * from repository tests (io.github.ngirchev.aibot.rest.repository, io.github.ngirchev.aibot.telegram.repository).
 * Without this class slice tests (@DataJpaTest) fail with
 * "Unable to find a @SpringBootConfiguration".
 */
@SpringBootConfiguration
public class RepositoryTestConfig {
}
