package io.github.ngirchev.opendaimon.ai.springai.retry;

import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenRouterFreeModelResolverTest {

    private static final String MODELS_JSON = """
            {
              "data": [
                {"id": "openrouter/foo:free", "pricing": {"prompt": "0", "completion": "0"}},
                {"id": "openrouter/bar:free", "pricing": {"prompt": "0.0", "completion": "0.0"}},
                {"id": "openrouter/baz", "pricing": {"prompt": "0.001", "completion": "0.002"}}
              ]
            }
            """;

    @Mock
    private RestTemplate restTemplate;

    private ObjectMapper objectMapper;
    private OpenRouterModelsProperties properties;
    private OpenRouterFreeModelResolver resolver;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new OpenRouterModelsProperties();
        properties.setEnabled(true);

        OpenRouterModelsProperties.Api api = new OpenRouterModelsProperties.Api();
        api.setKey("test-key");
        api.setUrl("https://openrouter.ai/api/v1/chat/completions");
        properties.setApi(api);

        OpenRouterModelsProperties.Ranking ranking = new OpenRouterModelsProperties.Ranking();
        ranking.setEnabled(true);
        ranking.setRetryMaxAttempts(3);
        ranking.setLatencyEwmaAlpha(0.2);
        ranking.setCooldown429(Duration.ofMinutes(5));
        ranking.setCooldown5xx(Duration.ofMinutes(2));
        properties.setRanking(ranking);

        properties.setFilters(new OpenRouterModelsProperties.Filters());

        when(restTemplate.exchange(
                contains("/v1/models"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.OK).body(MODELS_JSON));

        resolver = new OpenRouterFreeModelResolver(restTemplate, objectMapper, properties);
    }

    @Test
    void resolveIfAuto_nonAutoReturnsSame() {
        assertEquals("openrouter/foo:free", resolver.resolveIfAuto("openrouter/foo:free"));
        assertEquals("gpt-4", resolver.resolveIfAuto("gpt-4"));
    }

    @Test
    void resolveIfAuto_autoRefreshesAndReturnsFreeModel() {
        String resolved = resolver.resolveIfAuto(OpenRouterFreeModelResolver.OPENROUTER_AUTO);
        assertNotNull(resolved);
        assertTrue(resolved.contains(":free") || resolved.contains("openrouter"));
    }

    @Test
    void refresh_loadsAndCachesModels() {
        resolver.refresh();
        List<String> cached = resolver.getCachedFreeModelIds();
        assertFalse(cached.isEmpty());
        assertTrue(cached.stream().anyMatch(id -> id != null && id.contains(":free")));
        assertTrue(resolver.getLastRefreshAtEpochMs() > 0);
    }

    @Test
    void getCachedFreeModelIds_emptyBeforeRefresh() {
        List<String> cached = resolver.getCachedFreeModelIds();
        assertTrue(cached.isEmpty());
    }

    @Test
    void candidatesForAutoRequest_emptyWhenApiNotConfigured() {
        OpenRouterModelsProperties noApi = new OpenRouterModelsProperties();
        noApi.setEnabled(true);
        noApi.setApi(new OpenRouterModelsProperties.Api());
        noApi.setRanking(createDefaultRanking());
        OpenRouterFreeModelResolver r = new OpenRouterFreeModelResolver(restTemplate, objectMapper, noApi);
        assertTrue(r.candidatesForAutoRequest().isEmpty());
    }

    @Test
    void candidatesForAutoRequest_returnsCandidatesAfterRefresh() {
        resolver.refresh();
        List<String> candidates = resolver.candidatesForAutoRequest();
        assertFalse(candidates.isEmpty());
    }

    @Test
    void candidatesForModel_emptyWhenRequestedNull() {
        assertTrue(resolver.candidatesForModel(null, Collections.emptySet()).isEmpty());
        assertTrue(resolver.candidatesForModel("", Collections.emptySet()).isEmpty());
    }

    @Test
    void candidatesForModel_concreteModelReturnsSingle() {
        List<String> list = resolver.candidatesForModel("gpt-4", Collections.emptySet());
        assertEquals(List.of("gpt-4"), list);
    }

    @Test
    void candidatesForModel_autoDelegatesToCandidatesForAutoRequest() {
        resolver.refresh();
        List<String> list = resolver.candidatesForModel(OpenRouterFreeModelResolver.OPENROUTER_AUTO, Set.of(ModelCapabilities.CHAT));
        assertFalse(list.isEmpty());
    }

    @Test
    void candidatesForModel_freeModelReorderedFirst() {
        resolver.refresh();
        List<String> all = resolver.getCachedFreeModelIds();
        if (all.isEmpty()) return;
        String firstFree = all.stream().filter(id -> id != null && id.contains(":free")).findFirst().orElse(null);
        if (firstFree == null) return;
        List<String> reordered = resolver.candidatesForModel(firstFree, Collections.emptySet());
        assertFalse(reordered.isEmpty());
        assertEquals(firstFree, reordered.getFirst());
    }

    @Test
    void recordSuccess_updatesStats() {
        resolver.refresh();
        List<String> ids = resolver.getCachedFreeModelIds();
        if (!ids.isEmpty()) {
            resolver.recordSuccess(ids.get(0), 100L);
            List<String> candidates = resolver.candidatesForAutoRequest(Set.of());
            assertFalse(candidates.isEmpty());
        }
    }

    @Test
    void recordSuccess_ignoresBlankModelId() {
        assertDoesNotThrow(() -> resolver.recordSuccess("", 50L));
        assertDoesNotThrow(() -> resolver.recordSuccess(null, 50L));
    }

    @Test
    void recordFailure_updatesStats() {
        resolver.recordFailure("openrouter/foo:free", 429, 200L);
        resolver.recordFailure("openrouter/bar:free", 502, 150L);
        List<String> candidates = resolver.candidatesForAutoRequest();
        assertFalse(candidates.isEmpty());
    }

    @Test
    void recordFailure_ignoresBlankModelId() {
        assertDoesNotThrow(() -> resolver.recordFailure("", 500, 50L));
    }

    @Test
    void recordFailure_rankingDisabledStillUpdatesEwma() {
        properties.getRanking().setEnabled(false);
        resolver = new OpenRouterFreeModelResolver(restTemplate, objectMapper, properties);
        assertDoesNotThrow(() -> resolver.recordFailure("some-model", 500, 100L));
    }

    private static OpenRouterModelsProperties.Ranking createDefaultRanking() {
        OpenRouterModelsProperties.Ranking r = new OpenRouterModelsProperties.Ranking();
        r.setEnabled(false);
        r.setRetryMaxAttempts(1);
        r.setLatencyEwmaAlpha(0.2);
        r.setCooldown429(Duration.ofMinutes(5));
        r.setCooldown5xx(Duration.ofMinutes(2));
        return r;
    }
}
