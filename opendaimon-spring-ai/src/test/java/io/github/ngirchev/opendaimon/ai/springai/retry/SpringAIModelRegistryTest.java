package io.github.ngirchev.opendaimon.ai.springai.retry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringAIModelRegistryTest {

    private static final String MODELS_JSON = """
            {
              "data": [
                {
                  "id": "openai/gpt-5.4",
                  "pricing": {"prompt": "0.0000025", "completion": "0.000015"},
                  "architecture": {"modality": "text->text"},
                  "supported_parameters": ["tools"]
                },
                {
                  "id": "openai/gpt-5-nano",
                  "pricing": {"prompt": "0.00000005", "completion": "0.0000004"},
                  "architecture": {"modality": "text->text"},
                  "supported_parameters": ["tools"]
                },
                {
                  "id": "google/gemma-3-27b-it:free",
                  "pricing": {"prompt": "0", "completion": "0"},
                  "architecture": {"modality": "text->text"},
                  "supported_parameters": ["tools"]
                }
              ]
            }
            """;

    @Mock
    private RestTemplate restTemplate;

    private SpringAIModelRegistry registry;

    @BeforeEach
    void setUp() {
        when(restTemplate.exchange(
                contains("/v1/models"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.OK).body(MODELS_JSON));

        OpenRouterModelsApiClient client = new OpenRouterModelsApiClient(restTemplate, new ObjectMapper());

        OpenRouterModelsProperties props = new OpenRouterModelsProperties();
        props.setEnabled(true);

        OpenRouterModelsProperties.Api api = new OpenRouterModelsProperties.Api();
        api.setKey("test-key");
        api.setUrl("https://openrouter.ai/api");
        props.setApi(api);

        // Whitelist entry 1: ADMIN + VIP see paid models by exact ID
        OpenRouterModelsProperties.Whitelist adminVip = new OpenRouterModelsProperties.Whitelist();
        adminVip.setRoles(List.of(UserPriority.ADMIN, UserPriority.VIP));
        adminVip.setIncludeModelIds(List.of("openai/gpt-5.4", "openai/gpt-5-nano"));

        // Whitelist entry 2: ADMIN + REGULAR see free models by exact ID
        OpenRouterModelsProperties.Whitelist adminRegular = new OpenRouterModelsProperties.Whitelist();
        adminRegular.setRoles(List.of(UserPriority.ADMIN, UserPriority.REGULAR));
        adminRegular.setIncludeModelIds(List.of("google/gemma-3-27b-it:free"));

        props.setWhitelist(List.of(adminVip, adminRegular));

        registry = new SpringAIModelRegistry(List.of(), client, props);
    }

    @Test
    void paidModelMatchingWhitelistIsAddedToRegistry() {
        registry.refreshOpenRouterModels();

        List<SpringAIModelConfig> all = registry.getAllModels(null);
        assertThat(all).extracting(SpringAIModelConfig::getName)
                .contains("openai/gpt-5.4", "openai/gpt-5-nano");
    }

    @Test
    void paidModelIsVisibleToAdminAndVip() {
        registry.refreshOpenRouterModels();

        assertThat(registry.getAllModels(UserPriority.ADMIN))
                .extracting(SpringAIModelConfig::getName)
                .contains("openai/gpt-5.4");

        assertThat(registry.getAllModels(UserPriority.VIP))
                .extracting(SpringAIModelConfig::getName)
                .contains("openai/gpt-5.4");
    }

    @Test
    void paidModelIsNotVisibleToRegular() {
        registry.refreshOpenRouterModels();

        assertThat(registry.getAllModels(UserPriority.REGULAR))
                .extracting(SpringAIModelConfig::getName)
                .doesNotContain("openai/gpt-5.4", "openai/gpt-5-nano");
    }

    @Test
    void freeModelIsStillAddedForCorrectRoles() {
        registry.refreshOpenRouterModels();

        assertThat(registry.getAllModels(UserPriority.REGULAR))
                .extracting(SpringAIModelConfig::getName)
                .contains("google/gemma-3-27b-it:free");

        assertThat(registry.getAllModels(UserPriority.VIP))
                .extracting(SpringAIModelConfig::getName)
                .doesNotContain("google/gemma-3-27b-it:free");
    }

    @Test
    void paidModelNotMatchingWhitelistIsNotAdded() {
        registry.refreshOpenRouterModels();

        // openai/gpt-5.3-chat is not in whitelist — must not appear
        assertThat(registry.getAllModels(null))
                .extracting(SpringAIModelConfig::getName)
                .doesNotContain("openai/gpt-5.3-chat");
    }
}
