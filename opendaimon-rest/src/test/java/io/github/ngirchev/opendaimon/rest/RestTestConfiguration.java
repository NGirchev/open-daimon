package io.github.ngirchev.opendaimon.rest;

import org.springframework.boot.SpringBootConfiguration;

/**
 * Minimal configuration for @WebMvcTest in opendaimon-rest module.
 * Required because the module has no @SpringBootApplication.
 */
@SpringBootConfiguration
public class RestTestConfiguration {
}
