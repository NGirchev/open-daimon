package io.github.ngirchev.opendaimon.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ngirchev.dotenv.DotEnvLoader;
import io.github.ngirchev.opendaimon.rest.dto.ChatRequestDto;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.StringUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("manual")
@EnabledIfSystemProperty(named = "manual.openrouter.rest-contract", matches = "true")
class OpenRouterRestContractManualIT {

    private static final String OPENROUTER_KEY = "OPENROUTER_KEY";
    private static final String ADMIN_EMAIL = "admin@example.com";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        DotEnvLoader.loadDotEnv(Path.of("..", ".env"));
        DotEnvLoader.loadDotEnv();
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.ai.openai.api-key", OpenRouterRestContractManualIT::openRouterKeyOrPlaceholder);
    }

    @Test
    void restChatEndpointUsesRealOpenRouterProvider() {
        assumeTrue(StringUtils.hasText(openRouterKey()), "OPENROUTER_KEY is required for the OpenRouter contract test");

        var request = new ChatRequestDto(
                "What is 2 + 2? Reply with only the digit 4.",
                null,
                null,
                ADMIN_EMAIL);

        var response = restTemplate.postForEntity("/api/v1/session", request, JsonNode.class);

        assertThat(response.getStatusCode())
                .as(() -> response.getBody() != null ? response.getBody().toPrettyString() : "empty response body")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .isNotNull();
        assertThat(response.getBody().path("sessionId").asText())
                .isNotBlank();
        assertThat(response.getBody().path("message").asText())
                .contains("4");
    }

    private static String openRouterKeyOrPlaceholder() {
        String key = openRouterKey();
        return StringUtils.hasText(key) ? key : "missing-openrouter-key";
    }

    private static String openRouterKey() {
        return DotEnvLoader.getEnv(OPENROUTER_KEY);
    }
}
