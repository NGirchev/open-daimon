package ru.girchev.aibot.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "ai-bot.common")
@Validated
@Getter
@Setter
public class CoreCommonProperties {
    
    /**
     * Максимум токенов ответа в одном запросе к AI провайдеру (max_tokens в OpenRouter/OpenAI).
     * Передаётся в запросе и ограничивает длину ответа модели.
     * Для теста «токены закончились на ризонинге, контент пустой» временно задайте малое значение (например 50).
     */
    @NotNull(message = "maxOutputTokens обязателен")
    @Min(value = 1, message = "maxOutputTokens должен быть >= 1")
    private Integer maxOutputTokens;

    /**
     * Лимит токенов на рассуждение (reasoning/thinking) для моделей OpenRouter с поддержкой reasoning.
     * Передаётся как reasoning.max_tokens в extra_body. Опционально — если не задано, блок не отправляется.
     * Отдельно от max-output-tokens: тот лимитирует ответ, этот — «думание» (Anthropic, Gemini thinking и т.д.).
     */
    @Min(value = 1, message = "maxReasoningTokens должен быть >= 1")
    private Integer maxReasoningTokens;

    /**
     * Максимум токенов одного сообщения пользователя (текущий запрос).
     * При превышении можно отклонить запрос с подсказкой сократить сообщение.
     */
    @NotNull(message = "maxUserMessageTokens обязателен")
    @Min(value = 1, message = "maxUserMessageTokens должен быть >= 1")
    private Integer maxUserMessageTokens;

    /**
     * Максимум токенов всего промпта к API: system + история + текущее сообщение.
     * Не должен превышать лимит контекста модели провайдера (например 32k, 128k).
     */
    @NotNull(message = "maxTotalPromptTokens обязателен")
    @Min(value = 1000, message = "maxTotalPromptTokens должен быть >= 1000")
    private Integer maxTotalPromptTokens;

    @AssertTrue(message = "maxReasoningTokens должен быть < maxOutputTokens (общий лимит)")
    public boolean isMaxReasoningTokensValid() {
        return maxReasoningTokens == null || maxReasoningTokens < maxOutputTokens;
    }

    @NotBlank(message = "Описание роли ассистента не может быть пустым")
    private String assistantRole = "You are a helpful assistant, who talks with an old person and trying to help with new difficult world. You need to check your answers, because you shouldn't give an bad, wrong advises. Also, you prefer to answer shortly, without extra details if you were not asked about it. Also you are speaking only in Russian language.";
    
    /**
     * Параметры суммаризации длинных диалогов (триггер по токенам, порог, сколько последних сообщений не суммаризировать).
     */
    @Valid
    @NestedConfigurationProperty
    private SummarizationProperties summarization = new SummarizationProperties();

    /**
     * История диалога, управляемая common-модулем (ручной контекст).
     * Если enabled=true — используется ConversationHistoryAICommandFactory и ConversationContextBuilderService.
     * Если enabled=false — используется Spring AI ChatMemory.
     */
    @Valid
    @NestedConfigurationProperty
    private ManualConversationHistoryProperties manualConversationHistory = new ManualConversationHistoryProperties();

    /**
     * Конфигурация для инициализации администратора при старте приложения.
     */
    @Valid
    @NestedConfigurationProperty
    private AdminProperties admin = new AdminProperties();

    @Getter
    @Setter
    @Validated
    public static class SummarizationProperties {

        /**
         * Порог по токенам: при totalTokens в треде >= maxContextTokens * summaryTriggerThreshold запускается саммаризация.
         */
        @NotNull(message = "maxContextTokens обязателен")
        @Min(value = 1000, message = "maxContextTokens должен быть >= 1000")
        private Integer maxContextTokens;

        /**
         * Доля заполнения контекста для триггера саммаризации (0.0–1.0), например 0.7 = 70%.
         */
        @NotNull(message = "summaryTriggerThreshold обязателен")
        @Min(value = 0, message = "summaryTriggerThreshold должен быть >= 0.0")
        @Max(value = 1, message = "summaryTriggerThreshold должен быть <= 1.0")
        private Double summaryTriggerThreshold;

        /**
         * Сколько последних сообщений оставлять нетронутыми при фильтрации перед суммаризацией (async-путь).
         */
        @NotNull(message = "keepRecentMessages обязателен")
        @Min(value = 1, message = "keepRecentMessages должен быть >= 1")
        private Integer keepRecentMessages;
    }

    @Getter
    @Setter
    @Validated
    public static class ManualConversationHistoryProperties {

        /**
         * Включен ли режим ручного управления историей (common).
         * true — ConversationHistoryAICommandFactory и ConversationContextBuilderService.
         * false — Spring AI ChatMemory.
         */
        @NotNull(message = "enabled обязателен")
        private Boolean enabled;

        /**
         * Резерв под ответ модели при расчёте бюджета промпта.
         */
        @NotNull(message = "maxResponseTokens обязателен")
        @Min(value = 500, message = "maxResponseTokens должен быть >= 500")
        private Integer maxResponseTokens;

        /**
         * Количество последних сообщений для включения в контекст по умолчанию (ручной режим).
         */
        @NotNull(message = "defaultWindowSize обязателен")
        @Min(value = 1, message = "defaultWindowSize должен быть >= 1")
        private Integer defaultWindowSize;

        /**
         * Включать ли system prompt в каждый запрос (ручной режим).
         */
        @NotNull(message = "includeSystemPrompt обязателен")
        private Boolean includeSystemPrompt;

        /**
         * Грубая оценка: 1 токен ≈ N символов (для русского языка обычно 4).
         */
        @NotNull(message = "tokenEstimationCharsPerToken обязателен")
        @Min(value = 1, message = "tokenEstimationCharsPerToken должен быть >= 1")
        private Integer tokenEstimationCharsPerToken;
    }
    
    /**
     * Свойства для конфигурации администратора
     */
    @Getter
    @Setter
    @Validated
    public static class AdminProperties {
        
        /**
         * Включена ли инициализация администратора
         */
        private Boolean enabled = false;
        
        /**
         * Telegram ID администратора (опционально)
         */
        private Long telegramId;
        
        /**
         * Email REST администратора (опционально)
         */
        private String restEmail;
    }
} 