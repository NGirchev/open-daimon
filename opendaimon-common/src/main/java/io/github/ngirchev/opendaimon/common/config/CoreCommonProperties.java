package io.github.ngirchev.opendaimon.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "open-daimon.common")
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
    private String assistantRole = "role.content.default";
    
    /**
     * Summarization of long conversations (token trigger, threshold, how many recent messages to keep).
     */
    @Valid
    @NestedConfigurationProperty
    private SummarizationProperties summarization = new SummarizationProperties();

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
         * Context window size: max messages in ChatMemory window.
         * Used by SummarizingChatMemory (Spring AI) and by the UI to show context usage percentage.
         */
        @NotNull(message = "messageWindowSize is required")
        @Min(value = 1, message = "messageWindowSize must be >= 1")
        private Integer messageWindowSize;

        /**
         * Max tokens for the summarization response (summary + memory_bullets JSON).
         */
        @NotNull(message = "maxOutputTokens is required")
        @Min(value = 100, message = "maxOutputTokens must be >= 100")
        private Integer maxOutputTokens;

        /**
         * Prompt for the AI to produce summary and memory_bullets (JSON). Conversation is sent as separate user message.
         */
        @NotBlank(message = "prompt is required")
        private String prompt;
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