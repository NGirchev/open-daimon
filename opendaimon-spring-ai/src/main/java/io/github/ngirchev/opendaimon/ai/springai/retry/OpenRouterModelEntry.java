package io.github.ngirchev.opendaimon.ai.springai.retry;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * OpenRouter models list entry (GET /v1/models response).
 *
 * @param id     model identifier
 * @param free   true if pricing.prompt and pricing.completion are 0
 * @param node   raw model node for capabilities mapping
 */
public record OpenRouterModelEntry(String id, boolean free, JsonNode node) {
}
