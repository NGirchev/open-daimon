package io.github.ngirchev.opendaimon.starter;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;

public final class OpenDaimonDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String DEFAULTS_RESOURCE = "META-INF/opendaimon/opendaimon-defaults.yml";
    private static final String DEFAULTS_PROPERTY_SOURCE_NAME = "opendaimon-defaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(DEFAULTS_PROPERTY_SOURCE_NAME)) {
            return;
        }

        try {
            var resource = new ClassPathResource(DEFAULTS_RESOURCE);
            var loader = new YamlPropertySourceLoader();
            var propertySources = loader.load(DEFAULTS_PROPERTY_SOURCE_NAME, resource);
            for (var propertySource : propertySources.reversed()) {
                environment.getPropertySources().addLast(propertySource);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load OpenDaimon starter defaults", e);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
