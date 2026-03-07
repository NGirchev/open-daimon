package ru.girchev.aibot.ai.springai.config;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Конфигурация для multimodal функциональности (передача изображений в AI).
 * 
 * <p>Feature flag для включения/выключения поддержки изображений в запросах.
 * Когда выключено, все вложения игнорируются и отправляется только текст.
 */
@ConfigurationProperties(prefix = "ai-bot.ai.spring-ai.multimodal")
@Validated
@Getter
@Setter
public class MultimodalProperties {
    
    /**
     * Включить поддержку multimodal (изображения в запросах к AI).
     * По умолчанию выключено для обратной совместимости.
     */
    @NotNull(message = "multimodal.enabled обязателен")
    private Boolean enabled;
}
