package io.github.ngirchev.opendaimon.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class OpenDaimonDefaultsEnvironmentPostProcessorTest {

    private static final String SPRING_FACTORIES_RESOURCE = "META-INF/spring.factories";

    @Test
    void shouldRegisterEnvironmentPostProcessorInSpringFactories() {
        assertThat(loadResource(SPRING_FACTORIES_RESOURCE))
                .contains("org.springframework.boot.env.EnvironmentPostProcessor")
                .contains(OpenDaimonDefaultsEnvironmentPostProcessor.class.getName());
    }

    @Test
    void shouldLoadStarterDefaultsAtLowestPrecedence() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "consumer-application",
                Map.of(
                        "open-daimon.common.max-output-tokens", 1234,
                        "open-daimon.agent.max-iterations", 2)));

        new OpenDaimonDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("open-daimon.ai.spring-ai.enabled", Boolean.class))
                .isTrue();
        assertThat(environment.getProperty("open-daimon.common.storage.enabled", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("open-daimon.common.bulkhead.enabled", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("open-daimon.ai.spring-ai.rag.enabled", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("open-daimon.agent.enabled", Boolean.class))
                .isTrue();
        assertThat(environment.getProperty("spring.ai.openai.base-url"))
                .isEqualTo("https://openrouter.ai/api");
        assertThat(environment.getProperty("open-daimon.ai.spring-ai.url-check.enabled", Boolean.class))
                .isTrue();
        assertThat(environment.getProperty("open-daimon.ai.spring-ai.serper.api.key"))
                .isEmpty();
        assertThat(environment.getProperty("open-daimon.ai.spring-ai.models.list[0].capabilities[0]"))
                .isEqualTo("AUTO");
        assertThat(environment.getProperty("open-daimon.common.max-output-tokens", Integer.class))
                .isEqualTo(1234);
        assertThat(environment.getProperty("open-daimon.agent.max-iterations", Integer.class))
                .isEqualTo(2);
        assertThat(environment.containsProperty("open-daimon.rest.enabled"))
                .isFalse();
    }

    private String loadResource(String resourceName) {
        try (var input = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(resourceName)) {
            assertThat(input).as(resourceName).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
