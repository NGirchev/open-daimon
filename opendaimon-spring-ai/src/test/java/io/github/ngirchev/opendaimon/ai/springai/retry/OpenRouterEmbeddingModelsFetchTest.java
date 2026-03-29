package io.github.ngirchev.opendaimon.ai.springai.retry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link OpenRouterModelsApiClient#fetchEmbeddingModels} and the
 * {@link SpringAIModelRegistry} startup embedding model loading behaviour.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenRouterEmbeddingModelsFetchTest {

    private static final String BASE_URL = "https://openrouter.ai/api";
    private static final String API_KEY = "test-key";

    @Mock
    private RestTemplate restTemplate;

    private ObjectMapper objectMapper;
    private OpenRouterModelsApiClient client;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        client = new OpenRouterModelsApiClient(restTemplate, objectMapper);
    }

    // =========================================================================
    // fetchEmbeddingModels tests
    // =========================================================================

    @Nested
    @DisplayName("fetchEmbeddingModels")
    class FetchEmbeddingModelsTests {

        @Test
        @DisplayName("fetchesEmbeddingModels_success — parses count, ids, and free flags correctly")
        void fetchesEmbeddingModels_success() {
            String json = """
                    {
                      "data": [
                        {"id": "openai/text-embedding-ada-002",   "pricing": {"prompt": "0.0001", "completion": "0"}},
                        {"id": "openai/text-embedding-3-small",   "pricing": {"prompt": "0",      "completion": "0"}},
                        {"id": "cohere/embed-v3:free",            "pricing": {"prompt": "0",      "completion": "0"}}
                      ]
                    }
                    """;
            stubEmbeddingUrl(json);

            List<OpenRouterModelEntry> result = client.fetchEmbeddingModels(BASE_URL, API_KEY);

            assertThat(result).hasSize(3);
            assertThat(result).extracting(OpenRouterModelEntry::id)
                    .containsExactly(
                            "openai/text-embedding-ada-002",
                            "openai/text-embedding-3-small",
                            "cohere/embed-v3:free");
            // First model: prompt != 0 → not free
            assertThat(result.get(0).free()).isFalse();
            // Second and third: prompt=0, completion=0 → free
            assertThat(result.get(1).free()).isTrue();
            assertThat(result.get(2).free()).isTrue();
        }

        @Test
        @DisplayName("fetchesEmbeddingModels_emptyResponse — empty data array returns empty list")
        void fetchesEmbeddingModels_emptyResponse() {
            stubEmbeddingUrl("""
                    {"data": []}
                    """);

            List<OpenRouterModelEntry> result = client.fetchEmbeddingModels(BASE_URL, API_KEY);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("fetchesEmbeddingModels_nullResponse — API failure returns empty list")
        void fetchesEmbeddingModels_nullResponse() {
            when(restTemplate.exchange(
                    contains("/v1/embeddings/models"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(String.class)))
                    .thenThrow(new RuntimeException("Connection refused"));

            List<OpenRouterModelEntry> result = client.fetchEmbeddingModels(BASE_URL, API_KEY);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("fetchesEmbeddingModels_correctUrl — URL contains /v1/embeddings/models suffix")
        void fetchesEmbeddingModels_correctUrl() {
            ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
            when(restTemplate.exchange(
                    urlCaptor.capture(),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(String.class)))
                    .thenReturn(ResponseEntity.ok("""
                            {"data": []}
                            """));

            client.fetchEmbeddingModels(BASE_URL, API_KEY);

            assertThat(urlCaptor.getValue()).endsWith("/v1/embeddings/models");
            assertThat(urlCaptor.getValue()).startsWith(BASE_URL);
        }

        @Test
        @DisplayName("fetchesEmbeddingModels_skipsMissingIds — entries without id field are excluded")
        void fetchesEmbeddingModels_skipsMissingIds() {
            stubEmbeddingUrl("""
                    {
                      "data": [
                        {"pricing": {"prompt": "0", "completion": "0"}},
                        {"id": "",   "pricing": {"prompt": "0", "completion": "0"}},
                        {"id": "valid/embed-model", "pricing": {"prompt": "0", "completion": "0"}}
                      ]
                    }
                    """);

            List<OpenRouterModelEntry> result = client.fetchEmbeddingModels(BASE_URL, API_KEY);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo("valid/embed-model");
        }

        private void stubEmbeddingUrl(String responseJson) {
            when(restTemplate.exchange(
                    contains("/v1/embeddings/models"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(String.class)))
                    .thenReturn(ResponseEntity.status(HttpStatus.OK).body(responseJson));
        }
    }

    // =========================================================================
    // SpringAIModelRegistry startup / refresh embedding model tests
    // =========================================================================

    @Nested
    @DisplayName("SpringAIModelRegistry embedding model loading")
    class RegistryEmbeddingLoadingTests {

        private static final String CHAT_MODELS_JSON = """
                {
                  "data": [
                    {
                      "id": "google/gemma-3-27b-it:free",
                      "pricing": {"prompt": "0", "completion": "0"},
                      "architecture": {"modality": "text->text"},
                      "supported_parameters": ["tools"]
                    }
                  ]
                }
                """;

        private static final String EMBEDDING_MODELS_JSON = """
                {
                  "data": [
                    {"id": "openai/text-embedding-3-small", "pricing": {"prompt": "0",      "completion": "0"}},
                    {"id": "openai/text-embedding-ada-002", "pricing": {"prompt": "0.0001", "completion": "0"}},
                    {"id": "cohere/embed-v3:free",          "pricing": {"prompt": "0",      "completion": "0"}}
                  ]
                }
                """;

        private void stubChatUrl(String json) {
            when(restTemplate.exchange(
                    contains("/v1/models"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(String.class)))
                    .thenReturn(ResponseEntity.ok(json));
        }

        private void stubEmbeddingUrl(String json) {
            when(restTemplate.exchange(
                    contains("/v1/embeddings/models"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(String.class)))
                    .thenReturn(ResponseEntity.ok(json));
        }

        private OpenRouterModelsProperties buildProps() {
            OpenRouterModelsProperties props = new OpenRouterModelsProperties();
            props.setEnabled(true);

            OpenRouterModelsProperties.Api api = new OpenRouterModelsProperties.Api();
            api.setKey(API_KEY);
            api.setUrl(BASE_URL);
            props.setApi(api);

            return props;
        }

        @Test
        @DisplayName("loadEmbeddingModelsOnStartup_addsModelsToRegistry — models are present after construction")
        void loadEmbeddingModelsOnStartup_addsModelsToRegistry() {
            stubEmbeddingUrl(EMBEDDING_MODELS_JSON);

            OpenRouterModelsApiClient apiClient = new OpenRouterModelsApiClient(restTemplate, objectMapper);
            SpringAIModelRegistry registry = new SpringAIModelRegistry(List.of(), apiClient, buildProps());

            List<SpringAIModelConfig> all = registry.getAllModels(null);
            assertThat(all).extracting(SpringAIModelConfig::getName)
                    .contains(
                            "openai/text-embedding-3-small",
                            "openai/text-embedding-ada-002",
                            "cohere/embed-v3:free");
        }

        @Test
        @DisplayName("loadEmbeddingModelsOnStartup_skipsExistingModels — yml model is not duplicated")
        void loadEmbeddingModelsOnStartup_skipsExistingModels() {
            stubEmbeddingUrl(EMBEDDING_MODELS_JSON);

            // Pre-configure the same model via yml
            SpringAIModelConfig ymlConfig = new SpringAIModelConfig();
            ymlConfig.setName("openai/text-embedding-3-small");
            ymlConfig.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
            ymlConfig.setPriority(1);
            ymlConfig.setCapabilities(java.util.EnumSet.of(ModelCapabilities.EMBEDDING));

            OpenRouterModelsApiClient apiClient = new OpenRouterModelsApiClient(restTemplate, objectMapper);
            SpringAIModelRegistry registry = new SpringAIModelRegistry(List.of(ymlConfig), apiClient, buildProps());

            // Only one entry with that name should exist
            long count = registry.getAllModels(null).stream()
                    .filter(m -> "openai/text-embedding-3-small".equals(m.getName()))
                    .count();
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("loadEmbeddingModelsOnStartup_setsEmbeddingCapability — all loaded models have EMBEDDING")
        void loadEmbeddingModelsOnStartup_setsEmbeddingCapability() {
            stubEmbeddingUrl(EMBEDDING_MODELS_JSON);

            OpenRouterModelsApiClient apiClient = new OpenRouterModelsApiClient(restTemplate, objectMapper);
            SpringAIModelRegistry registry = new SpringAIModelRegistry(List.of(), apiClient, buildProps());

            List<SpringAIModelConfig> all = registry.getAllModels(null);
            assertThat(all).isNotEmpty();
            assertThat(all)
                    .allMatch(m -> m.getCapabilities() != null
                            && m.getCapabilities().contains(ModelCapabilities.EMBEDDING));
        }

        @Test
        @DisplayName("loadEmbeddingModelsOnStartup_freeModelsGetFreeCapability — free models have FREE capability")
        void loadEmbeddingModelsOnStartup_freeModelsGetFreeCapability() {
            stubEmbeddingUrl(EMBEDDING_MODELS_JSON);

            OpenRouterModelsApiClient apiClient = new OpenRouterModelsApiClient(restTemplate, objectMapper);
            SpringAIModelRegistry registry = new SpringAIModelRegistry(List.of(), apiClient, buildProps());

            // openai/text-embedding-3-small has prompt=0,completion=0 → free
            SpringAIModelConfig freeModel = registry.getByModelName("openai/text-embedding-3-small")
                    .orElseThrow(() -> new AssertionError("Model not found in registry"));
            assertThat(freeModel.getCapabilities()).contains(ModelCapabilities.FREE);

            // cohere/embed-v3:free also free
            SpringAIModelConfig cohereModel = registry.getByModelName("cohere/embed-v3:free")
                    .orElseThrow(() -> new AssertionError("Cohere model not found"));
            assertThat(cohereModel.getCapabilities()).contains(ModelCapabilities.FREE);

            // openai/text-embedding-ada-002 has prompt=0.0001 → not free
            SpringAIModelConfig paidModel = registry.getByModelName("openai/text-embedding-ada-002")
                    .orElseThrow(() -> new AssertionError("Ada model not found"));
            assertThat(paidModel.getCapabilities()).doesNotContain(ModelCapabilities.FREE);
        }

        @Test
        @DisplayName("refreshOpenRouterModels_alsoFetchesEmbeddingModels — refresh adds both chat and embedding models")
        void refreshOpenRouterModels_alsoFetchesEmbeddingModels() {
            // On startup only embedding models are fetched; chat models come via refresh.
            stubEmbeddingUrl(EMBEDDING_MODELS_JSON);

            OpenRouterModelsApiClient apiClient = new OpenRouterModelsApiClient(restTemplate, objectMapper);
            SpringAIModelRegistry registry = new SpringAIModelRegistry(List.of(), apiClient, buildProps());

            // Now stub both endpoints for the refresh call
            stubChatUrl(CHAT_MODELS_JSON);

            registry.refreshOpenRouterModels();

            List<SpringAIModelConfig> all = registry.getAllModels(null);
            // Free chat model added by refresh (paid models require a whitelist)
            assertThat(all).extracting(SpringAIModelConfig::getName)
                    .contains("google/gemma-3-27b-it:free");
            // Embedding models still present (not removed during refresh)
            assertThat(all).extracting(SpringAIModelConfig::getName)
                    .contains(
                            "openai/text-embedding-3-small",
                            "openai/text-embedding-ada-002",
                            "cohere/embed-v3:free");
        }
    }
}
