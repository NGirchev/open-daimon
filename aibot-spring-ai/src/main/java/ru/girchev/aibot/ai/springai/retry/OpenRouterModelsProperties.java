package ru.girchev.aibot.ai.springai.retry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;

/**
 * Настройки обновления списка моделей OpenRouter и ранжирования (refresh, ranking).
 * Фильтр по бесплатным моделям задаётся в filters (например, include-model-ids).
 *
 * Важно: без дефолтов в коде, всё задаётся в application.yml.
 */
@ConfigurationProperties(prefix = "ai-bot.ai.spring-ai.openrouter-auto-rotation.models")
@Validated
@Getter
@Setter
public class OpenRouterModelsProperties {

    /**
     * Включить/выключить фоновое обновление списка моделей с OpenRouter и ранжирование.
     */
    @NotNull(message = "enabled обязателен")
    private Boolean enabled;

    @Valid
    @NestedConfigurationProperty
    private Api api;

    private Duration refreshInterval;

    private Duration refreshInitialDelay;

    @Valid
    @NestedConfigurationProperty
    private Ranking ranking;

    @Valid
    @NestedConfigurationProperty
    private Filters filters;

    @Getter
    @Setter
    public static class Api {
        /**
         * OpenRouter API key.
         */
        private String key;

        /**
         * Base URL OpenRouter API. Пример: https://openrouter.ai/api
         * Также допустимо передать полный URL до /v1/chat/completions — будет нормализован.
         */
        private String url;
    }

    @Getter
    @Setter
    public static class Ranking {
        /**
         * Включить ранжирование по ошибкам/латентности на основе РЕАЛЬНЫХ запросов.
         */
        @NotNull(message = "ranking.enabled обязателен")
        private Boolean enabled;

        /**
         * Максимальное число попыток (моделей) при retry для одного боевого запроса.
         */
        @NotNull(message = "ranking.retryMaxAttempts обязателен")
        private Integer retryMaxAttempts;

        /**
         * EWMA alpha для latency (0..1). Ближе к 1 = быстрее реагирует на изменения.
         */
        @NotNull(message = "ranking.latencyEwmaAlpha обязателен")
        private Double latencyEwmaAlpha;

        /**
         * cooldown после 429 (лимиты/перегруз).
         */
        @NotNull(message = "ranking.cooldown429 обязателен")
        private Duration cooldown429;

        /**
         * cooldown после 5xx (ошибки провайдера/маршрутизации).
         */
        @NotNull(message = "ranking.cooldown5xx обязателен")
        private Duration cooldown5xx;
    }

    @Getter
    @Setter
    public static class Filters {
        /**
         * Allowlist: если задан, то будут использоваться только модели из списка.
         */
        private List<String> includeModelIds;

        /**
         * Allowlist по подстрокам: если задан, то будут использоваться только модели,
         * id которых содержит любой из этих фрагментов.
         */
        private List<String> includeContains;

        /**
         * Явный blacklist моделей, которые не подходят для нашего пайплайна (например, возвращают 400 из-за формата messages).
         */
        private List<String> excludeModelIds;

        /**
         * Исключить модели, id которых содержит любой из этих фрагментов.
         */
        private List<String> excludeContains;
    }

    @AssertTrue(message = "При enabled=true должны быть заданы api.key, api.url, refresh-initial-delay и refresh-interval (оба > 0)")
    public boolean isValidWhenEnabled() {
        return !Boolean.TRUE.equals(enabled)
                || (api != null
                && StringUtils.hasText(api.key)
                && StringUtils.hasText(api.url)
                && refreshInitialDelay != null
                && refreshInterval != null
                && !refreshInitialDelay.isZero()
                && !refreshInitialDelay.isNegative()
                && !refreshInterval.isZero()
                && !refreshInterval.isNegative());
    }

    @AssertTrue(message = "При enabled=true и ranking.enabled=true должны быть заданы ranking.* и они должны быть валидными")
    public boolean isValidRankingWhenEnabled() {
        if (!Boolean.TRUE.equals(enabled)) {
            return true;
        }
        if (ranking == null || !Boolean.TRUE.equals(ranking.enabled)) {
            return true;
        }
        if (ranking.retryMaxAttempts == null || ranking.retryMaxAttempts <= 0) {
            return false;
        }
        if (ranking.latencyEwmaAlpha == null || ranking.latencyEwmaAlpha <= 0.0d || ranking.latencyEwmaAlpha > 1.0d) {
            return false;
        }
        if (ranking.cooldown429 == null || ranking.cooldown429.isZero() || ranking.cooldown429.isNegative()) {
            return false;
        }
        if (ranking.cooldown5xx == null || ranking.cooldown5xx.isZero() || ranking.cooldown5xx.isNegative()) {
            return false;
        }
        return true;
    }
}
