package io.github.ngirchev.aibot.rest;

import org.springframework.boot.SpringBootConfiguration;

/**
 * Minimal configuration for @WebMvcTest in aibot-rest module.
 * Required because the module has no @SpringBootApplication.
 */
@SpringBootConfiguration
public class RestTestConfiguration {
}
