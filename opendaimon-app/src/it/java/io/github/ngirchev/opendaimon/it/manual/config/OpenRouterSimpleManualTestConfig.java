package io.github.ngirchev.opendaimon.it.manual.config;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Shared Spring Boot test configuration for manual OpenRouter tests that do not need
 * custom beans (e.g., WebTools). Tests sharing this config will reuse the same
 * Spring context, reducing connection pool overhead.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class OpenRouterSimpleManualTestConfig {
}
