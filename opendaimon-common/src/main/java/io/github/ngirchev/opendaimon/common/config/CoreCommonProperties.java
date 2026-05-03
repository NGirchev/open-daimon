package io.github.ngirchev.opendaimon.common.config;

import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

import java.util.List;

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
     * Token budget for reasoning/thinking (OpenRouter: {@code extra_body.reasoning.max_tokens}).
     * For Ollama, there is no separate API field; when thinking is enabled, this budget is added to
     * {@code num_predict} alongside {@link #maxOutputTokens}. Optional; if not set, the block is not sent (OpenRouter)
     * and no extra headroom is added (Ollama).
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
     * AI command routing by user priority. YAML uses {@code ADMIN} / {@code VIP} / {@code REGULAR} keys
     * (same style as {@code open-daimon.telegram.access}); Java fields are {@code admin}, {@code vip}, {@code regular}.
     */
    @Valid
    @NotNull(message = "chatRouting is required")
    @NestedConfigurationProperty
    private ChatRoutingProperties chatRouting;

    @AssertTrue(message = "chatRouting.admin.maxPrice is required")
    public boolean isAdminChatRoutingMaxPricePresent() {
        return chatRouting != null && chatRouting.getAdmin() != null && chatRouting.getAdmin().getMaxPrice() != null;
    }

    @AssertTrue(message = "chatRouting.vip.maxPrice is required")
    public boolean isVipChatRoutingMaxPricePresent() {
        return chatRouting != null && chatRouting.getVip() != null && chatRouting.getVip().getMaxPrice() != null;
    }

    @AssertTrue(message = "chatRouting.regular.maxPrice is required")
    public boolean isRegularChatRoutingMaxPricePresent() {
        return chatRouting != null && chatRouting.getRegular() != null && chatRouting.getRegular().getMaxPrice() != null;
    }

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
         * Max tokens for the context window. When exceeded, triggers summarization.
         * Used by SummarizingChatMemory as second trigger (first is messageWindowSize).
         * Also used by UI to show context usage percentage (shows max of message% and token%).
         */
        @NotNull(message = "maxWindowTokens is required")
        @Min(value = 100, message = "maxWindowTokens must be >= 100")
        private Integer maxWindowTokens;

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
     * Nested {@code ADMIN} / {@code VIP} / {@code REGULAR} blocks under {@code open-daimon.common.chat-routing}.
     */
    @Getter
    @Setter
    @Validated
    public static class ChatRoutingProperties {

        @NotNull(message = "chatRouting.admin is required")
        @Valid
        @NestedConfigurationProperty
        private PriorityChatRoutingProperties admin;

        @NotNull(message = "chatRouting.vip is required")
        @Valid
        @NestedConfigurationProperty
        private PriorityChatRoutingProperties vip;

        @NotNull(message = "chatRouting.regular is required")
        @Valid
        @NestedConfigurationProperty
        private PriorityChatRoutingProperties regular;
    }

    /**
     * Routing for one user-priority tier in {@link io.github.ngirchev.opendaimon.common.ai.factory.DefaultAICommandFactory}.
     * <ul>
     *   <li>{@code max-price}: OpenRouter {@code max_price} for ADMIN, VIP, and REGULAR (required for all tiers).</li>
     *   <li>{@code required-capabilities} / {@code optional-capabilities}: model selection (VIP typically {@code CHAT} required).</li>
     * </ul>
     */
    @Getter
    @Setter
    @Validated
    public static class PriorityChatRoutingProperties {

        /**
         * When non-null, sent as {@code max_price} in the request extra body (OpenRouter).
         */
        @DecimalMin(value = "0.0", inclusive = true, message = "maxPrice must be >= 0")
        private Double maxPrice;

        /**
         * Required capabilities for model selection.
         */
        @NotNull(message = "requiredCapabilities is required")
        @NotEmpty(message = "requiredCapabilities must not be empty")
        private List<ModelCapabilities> requiredCapabilities;

        /**
         * Preferred but non-required capabilities. Use an empty list for none.
         */
        @NotNull(message = "optionalCapabilities is required")
        private List<ModelCapabilities> optionalCapabilities;
    }
}
