package ru.girchev.aibot.ai.springai.retry;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Элемент списка моделей OpenRouter (ответ GET /v1/models).
 *
 * @param id     идентификатор модели
 * @param free   true, если pricing.prompt и pricing.completion равны 0
 * @param node   сырой узел модели для маппинга capabilities
 */
public record OpenRouterModelEntry(String id, boolean free, JsonNode node) {
}
