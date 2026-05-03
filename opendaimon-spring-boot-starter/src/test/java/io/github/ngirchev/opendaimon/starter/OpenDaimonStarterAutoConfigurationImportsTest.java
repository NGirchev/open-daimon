package io.github.ngirchev.opendaimon.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class OpenDaimonStarterAutoConfigurationImportsTest {

    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @Test
    void shouldExposeCommonAndSpringAiAutoConfigurations() {
        assertThat(loadAutoConfigurationImports())
                .containsExactly(
                        "io.github.ngirchev.opendaimon.common.config.CoreAutoConfig",
                        "io.github.ngirchev.opendaimon.bulkhead.config.BulkHeadAutoConfig",
                        "io.github.ngirchev.opendaimon.common.storage.config.StorageAutoConfig",
                        "io.github.ngirchev.opendaimon.ai.springai.config.SpringAIAutoConfig",
                        "io.github.ngirchev.opendaimon.ai.springai.config.RAGAutoConfig",
                        "io.github.ngirchev.opendaimon.ai.springai.config.AgentAutoConfig");
    }

    private List<String> loadAutoConfigurationImports() {
        try (var input = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(IMPORTS_RESOURCE)) {
            assertThat(input).as("starter auto-configuration imports resource").isNotNull();

            var content = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            return content.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
