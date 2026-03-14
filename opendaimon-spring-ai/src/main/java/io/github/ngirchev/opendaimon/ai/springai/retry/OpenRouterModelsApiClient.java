package io.github.ngirchev.opendaimon.ai.springai.retry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin client for fetching OpenRouter models list (GET /v1/models).
 * Stateless for ranking; used by registry on refresh.
 */
@Slf4j
@RequiredArgsConstructor
public class OpenRouterModelsApiClient {

    private static final String PRICING_PROMPT = "prompt";
    private static final String PRICING_COMPLETION = "completion";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Fetches full models list from OpenRouter.
     *
     * @param baseUrl base API URL (e.g. https://openrouter.ai/api)
     * @param apiKey  OpenRouter API key
     * @return list of entries (id, free, node); empty on error or empty response
     */
    public List<OpenRouterModelEntry> fetchModels(String baseUrl, String apiKey) {
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(apiKey)) {
            log.warn("OpenRouter models fetch skipped: baseUrl or apiKey is empty");
            return List.of();
        }
        String modelsUrl = baseUrl.endsWith("/") ? baseUrl + "v1/models" : baseUrl + "/v1/models";
        JsonNode root = fetchJson(modelsUrl, apiKey);
        if (root == null || root.isMissingNode()) {
            return List.of();
        }
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            return List.of();
        }
        List<OpenRouterModelEntry> result = new ArrayList<>();
        for (JsonNode m : data) {
            String id = m.path("id").asText(null);
            if (!StringUtils.hasText(id)) {
                continue;
            }
            JsonNode pricing = m.path("pricing");
            boolean free = !pricing.isMissingNode() && !pricing.isNull()
                    && isZero(pricing.path(PRICING_PROMPT))
                    && isZero(pricing.path(PRICING_COMPLETION));
            result.add(new OpenRouterModelEntry(id, free, m));
        }
        log.info("OpenRouter models fetch: {} models (free: {})", result.size(),
                result.stream().filter(OpenRouterModelEntry::free).count());
        return result;
    }

    private JsonNode fetchJson(String url, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String body = response.getBody();
            return objectMapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            log.warn("OpenRouter models fetch failed: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isZero(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return false;
        }
        String text = n.asText();
        if (!StringUtils.hasText(text)) {
            return false;
        }
        try {
            return Double.parseDouble(text.trim()) == 0.0d;
        } catch (Exception ignored) {
            return false;
        }
    }
}
