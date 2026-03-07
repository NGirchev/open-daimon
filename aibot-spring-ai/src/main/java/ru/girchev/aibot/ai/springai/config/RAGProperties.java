package ru.girchev.aibot.ai.springai.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Конфигурация для RAG (Retrieval-Augmented Generation) с SimpleVectorStore.
 * 
 * <p>Feature flag: {@code ai-bot.ai.spring-ai.rag.enabled}
 * 
 * <p>SimpleVectorStore хранит данные in-memory, что означает:
 * <ul>
 *   <li>Быстрая работа для тестирования и небольших объемов</li>
 *   <li>Данные теряются при перезапуске</li>
 *   <li>Для production рекомендуется PGVector или Elasticsearch</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "ai-bot.ai.spring-ai.rag")
@Validated
@Getter
@Setter
public class RAGProperties {

    /**
     * Размер чанка в токенах при разбиении документа.
     * Оптимальный размер для RAG обычно 500-1000 токенов.
     */
    @NotNull(message = "chunkSize обязателен")
    @Min(value = 100, message = "chunkSize должен быть >= 100")
    private Integer chunkSize;

    /**
     * Количество токенов перекрытия между чанками.
     * Перекрытие помогает сохранить контекст на границах чанков.
     */
    @NotNull(message = "chunkOverlap обязателен")
    @Min(value = 0, message = "chunkOverlap должен быть >= 0")
    private Integer chunkOverlap;

    /**
     * Количество топ-K релевантных чанков для извлечения при поиске.
     */
    @NotNull(message = "topK обязателен")
    @Min(value = 1, message = "topK должен быть >= 1")
    private Integer topK;

    /**
     * Минимальный порог similarity для включения документа в результаты.
     * Значение от 0.0 до 1.0, где 1.0 - полное совпадение.
     */
    @NotNull(message = "similarityThreshold обязателен")
    private Double similarityThreshold;
}
