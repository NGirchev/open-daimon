package io.github.ngirchev.aibot.common.config;

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
     * Max tokens for a single response from the AI provider (max_tokens in OpenRouter/OpenAI).
     * Sent in the request and limits the model response length.
     */
    @NotNull(message = "maxOutputTokens is required")
    @Min(value = 1, message = "maxOutputTokens must be >= 1")
    private Integer maxOutputTokens;

    /**
     * Token budget for reasoning/thinking (OpenRouter models with reasoning support).
     * Sent as reasoning.max_tokens in extra_body. Optional; if not set, the block is not sent.
     */
    @Min(value = 1, message = "maxReasoningTokens must be >= 1")
    private Integer maxReasoningTokens;

    /**
     * Max tokens for a single user message (current request).
     */
    @NotNull(message = "maxUserMessageTokens is required")
    @Min(value = 1, message = "maxUserMessageTokens must be >= 1")
    private Integer maxUserMessageTokens;

    /**
     * Max total prompt tokens to the API: system + history + current message.
     */
    @NotNull(message = "maxTotalPromptTokens is required")
    @Min(value = 1000, message = "maxTotalPromptTokens must be >= 1000")
    private Integer maxTotalPromptTokens;

    @AssertTrue(message = "maxReasoningTokens must be < maxOutputTokens")
    public boolean isMaxReasoningTokensValid() {
        return maxReasoningTokens == null || maxReasoningTokens < maxOutputTokens;
    }

    @NotBlank(message = "assistantRole must not be blank")
    private String assistantRole = "You are a helpful assistant, who talks with an old person and trying to help with new difficult world. You need to check your answers, because you shouldn't give an bad, wrong advises. Also, you prefer to answer shortly, without extra details if you were not asked about it. Also you are speaking only in Russian language.";
    
    /**
     * Summarization of long conversations (token trigger, threshold, how many recent messages to keep).
     */
    @Valid
    @NestedConfigurationProperty
    private SummarizationProperties summarization = new SummarizationProperties();

    /**
     * Conversation history managed by common module (manual context).
     * enabled=true: ConversationHistoryAICommandFactory and ConversationContextBuilderService.
     * enabled=false: Spring AI ChatMemory.
     */
    @Valid
    @NestedConfigurationProperty
    private ManualConversationHistoryProperties manualConversationHistory = new ManualConversationHistoryProperties();

    /**
     * Admin initialization at application startup.
     */
    @Valid
    @NestedConfigurationProperty
    private AdminProperties admin = new AdminProperties();

    @Getter
    @Setter
    @Validated
    public static class SummarizationProperties {

        /**
         * When totalTokens in thread >= maxContextTokens * summaryTriggerThreshold, summarization runs.
         */
        @NotNull(message = "maxContextTokens is required")
        @Min(value = 1000, message = "maxContextTokens must be >= 1000")
        private Integer maxContextTokens;

        /**
         * Context fill ratio to trigger summarization (0.0–1.0), e.g. 0.7 = 70%.
         */
        @NotNull(message = "summaryTriggerThreshold is required")
        @Min(value = 0, message = "summaryTriggerThreshold must be >= 0.0")
        @Max(value = 1, message = "summaryTriggerThreshold must be <= 1.0")
        private Double summaryTriggerThreshold;

        /**
         * How many recent messages to leave untouched when filtering before summarization (async path).
         */
        @NotNull(message = "keepRecentMessages is required")
        @Min(value = 1, message = "keepRecentMessages must be >= 1")
        private Integer keepRecentMessages;

        /**
         * Prompt for the AI to produce summary and memory_bullets (JSON). Conversation is sent as separate user message.
         */
        @NotBlank(message = "prompt is required")
        private String prompt;
    }

    @Getter
    @Setter
    @Validated
    public static class ManualConversationHistoryProperties {

        /**
         * Whether manual conversation history (common) is enabled.
         * true: ConversationHistoryAICommandFactory and ConversationContextBuilderService.
         * false: Spring AI ChatMemory.
         */
        @NotNull(message = "enabled is required")
        private Boolean enabled;

        /**
         * Reserve for model response when calculating prompt budget.
         */
        @NotNull(message = "maxResponseTokens is required")
        @Min(value = 500, message = "maxResponseTokens must be >= 500")
        private Integer maxResponseTokens;

        /**
         * Default number of recent messages to include in context (manual mode).
         */
        @NotNull(message = "defaultWindowSize is required")
        @Min(value = 1, message = "defaultWindowSize must be >= 1")
        private Integer defaultWindowSize;

        /**
         * Whether to include system prompt in every request (manual mode).
         */
        @NotNull(message = "includeSystemPrompt is required")
        private Boolean includeSystemPrompt;

        /**
         * Rough estimate: 1 token ≈ N characters.
         */
        @NotNull(message = "tokenEstimationCharsPerToken is required")
        @Min(value = 1, message = "tokenEstimationCharsPerToken must be >= 1")
        private Integer tokenEstimationCharsPerToken;
    }
    
    /**
     * Admin configuration properties.
     */
    @Getter
    @Setter
    @Validated
    public static class AdminProperties {

        /**
         * Whether to run admin initialization.
         */
        private Boolean enabled = false;

        /**
         * Admin Telegram ID (optional).
         */
        private Long telegramId;

        /**
         * Admin REST email (optional).
         */
        private String restEmail;
    }
} 