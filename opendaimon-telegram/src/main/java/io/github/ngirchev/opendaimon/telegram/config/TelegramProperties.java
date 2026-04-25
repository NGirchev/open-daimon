package io.github.ngirchev.opendaimon.telegram.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.HashSet;
import java.util.Set;


@Slf4j
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "open-daimon.telegram")
public class TelegramProperties {
    
    @NotBlank(message = "Bot token cannot be blank")
    private String token;
    
    @NotBlank(message = "Bot username cannot be blank")
    private String username;

    /**
     * Returns the bot username prefixed with @ (normalized form for mentions).
     */
    public String getNormalizedBotUsername() {
        if (username == null) {
            return null;
        }
        String trimmed = username.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        return trimmed.startsWith("@") ? trimmed : "@" + trimmed;
    }

    /**
     * Access configuration for user priority levels.
     * Supports both environment variables and direct configuration.
     */
    private AccessConfig access = new AccessConfig();
    
    /**
     * Enable/disable settings for command handlers.
     */
    private Commands commands = new Commands();

    /**
     * Coalescing settings for Telegram two-step user inputs (e.g. text + forwarded/media message).
     */
    private MessageCoalescing messageCoalescing = new MessageCoalescing();

    @Getter
    @Setter
    public static class AccessConfig {
        private LevelConfig admin = new LevelConfig();
        private LevelConfig vip = new LevelConfig();
        private LevelConfig regular = new LevelConfig();

        @Getter
        @Setter
        public static class LevelConfig {
            private Set<Long> ids = new HashSet<>();
            private Set<String> channels = new HashSet<>();
            private Set<String> emails = new HashSet<>();
            /**
             * When true, users not matching any ids/channels/whitelist for this level are BLOCKED
             * instead of falling through to REGULAR. Effectively makes access opt-in (whitelist-only).
             */
            private boolean defaultBlocked = false;
        }
    }

    /**
     * HTTP read timeout for long polling (seconds). Must be strictly greater than get-updates-timeout-seconds.
     * Optional; when absent, telegrambots library defaults are used.
     */
    @Min(value = 1, message = "longPollingSocketTimeoutSeconds must be >= 1")
    @Max(value = 100, message = "longPollingSocketTimeoutSeconds must be <= 100")
    private Integer longPollingSocketTimeoutSeconds;

    /**
     * getUpdates timeout parameter (seconds). Maximum 50 per Telegram API docs.
     * Optional; when absent, telegrambots library defaults are used.
     */
    @Min(value = 1, message = "getUpdatesTimeoutSeconds must be >= 1")
    @Max(value = 50, message = "getUpdatesTimeoutSeconds must be <= 50")
    private Integer getUpdatesTimeoutSeconds;

    /**
     * Maximum message length for sending to Telegram (characters).
     * Default 4096 (Telegram Bot API limit).
     * When exceeded, message is split at paragraph boundaries.
     */
    @NotNull(message = "maxMessageLength is required")
    @Min(value = 100, message = "maxMessageLength must be >= 100")
    @Max(value = 10000, message = "maxMessageLength must be <= 10000")
    private Integer maxMessageLength;

    /**
     * Minimum interval between consecutive paced phase-transition flushes on the
     * same {@code MessageHandlerContext} for agent streaming (milliseconds).
     *
     * <p><strong>UX phase pacing, not Telegram quota enforcement.</strong> Real
     * Telegram-quota rate limiting is delegated to {@code TelegramChatRateLimiter}
     * (chat-scoped + global). This per-context throttle just smooths visual
     * "jumps" between phases (think → tool call → observation) so the user has
     * time to register each state — bypassing it does not produce a 429, but the
     * stream looks jittery.
     */
    @NotNull(message = "agentStreamEditMinIntervalMs is required")
    @Min(value = 0, message = "agentStreamEditMinIntervalMs must be >= 0")
    @Max(value = 10000, message = "agentStreamEditMinIntervalMs must be <= 10000")
    private Integer agentStreamEditMinIntervalMs;

    /**
     * Outbound rate limiting against Telegram-side quotas. Applied to every
     * outgoing operation by {@code TelegramChatRateLimiter}. See
     * {@code TELEGRAM_MODULE.md} §"Outbound rate limiting" for the source of
     * the numerical defaults (Telegram FAQ + field observations).
     */
    @NotNull(message = "rateLimit is required")
    @Valid
    private RateLimit rateLimit;

    @Getter
    @Setter
    public static class Commands {
        /**
         * Enable/disable /start command handler
         */
        private boolean startEnabled;
        
        /**
         * Enable/disable /role command handler
         */
        private boolean roleEnabled;
        
        /**
         * Enable/disable regular message handler
         */
        private boolean messageEnabled;

        /**
         * Enable/disable /bugreport command handler
         */
        private boolean bugreportEnabled;

        /**
         * Enable/disable /newthread command handler
         */
        private boolean newthreadEnabled;

        /**
         * Enable/disable /history command handler
         */
        private boolean historyEnabled;

        /**
         * Enable/disable /threads command handler
         */
        private boolean threadsEnabled;

        /**
         * Enable/disable /language command handler
         */
        private boolean languageEnabled;

        /**
         * Enable/disable /model command handler
         */
        private boolean modelEnabled = true;
    }

    @Getter
    @Setter
    public static class MessageCoalescing {
        /**
         * Enables two-update coalescing for "prefix text + related next message" scenarios.
         */
        private boolean enabled = true;

        /**
         * Wait window for the second update (milliseconds).
         */
        @Min(value = 100, message = "waitWindowMs must be >= 100")
        @Max(value = 5000, message = "waitWindowMs must be <= 5000")
        private int waitWindowMs = 1200;

        /**
         * Maximum length of the first text candidate to hold for coalescing.
         */
        @Min(value = 1, message = "maxLeadingTextLength must be >= 1")
        @Max(value = 1000, message = "maxLeadingTextLength must be <= 1000")
        private int maxLeadingTextLength = 160;

        /**
         * Allows photo/document as the second message in a coalesced pair.
         */
        private boolean allowMediaSecondMessage = true;

        /**
         * Requires explicit relation on second message (forward origin or reply-to first message).
         */
        private boolean requireExplicitLink = true;
    }
    
    @Getter
    @Setter
    @Validated
    public static class RateLimit {

        /**
         * Per-chat capacity for private chats per second (Telegram FAQ:
         * "avoid sending more than one message per second" in a single chat).
         */
        @NotNull(message = "privateChatPerSecond is required")
        @Min(value = 1, message = "privateChatPerSecond must be >= 1")
        @Max(value = 10, message = "privateChatPerSecond must be <= 10")
        private Integer privateChatPerSecond;

        /**
         * Per-chat capacity for group/supergroup chats per minute (Telegram FAQ:
         * "bots are not be able to send more than 20 messages per minute" in a group).
         */
        @NotNull(message = "groupChatPerMinute is required")
        @Min(value = 1, message = "groupChatPerMinute must be >= 1")
        @Max(value = 60, message = "groupChatPerMinute must be <= 60")
        private Integer groupChatPerMinute;

        /**
         * Practical floor between consecutive edits in a group/supergroup
         * (milliseconds). Field observation: even below 20/min, edits at less
         * than ~3 sec apart trigger 429 with long retry windows.
         */
        @NotNull(message = "groupChatMinEditIntervalMs is required")
        @Min(value = 0, message = "groupChatMinEditIntervalMs must be >= 0")
        @Max(value = 30000, message = "groupChatMinEditIntervalMs must be <= 30000")
        private Integer groupChatMinEditIntervalMs;

        /**
         * Global per-bot ceiling (Telegram FAQ: "30 messages per second" total
         * across all chats). Applied as a sliding window of 1 second.
         */
        @NotNull(message = "globalPerSecond is required")
        @Min(value = 1, message = "globalPerSecond must be >= 1")
        @Max(value = 30, message = "globalPerSecond must be <= 30")
        private Integer globalPerSecond;

        /**
         * Maximum time {@code acquire} blocks while waiting for a slot when
         * opening a new bubble (sendMessage that returns a message id we want
         * to keep editing). Past this window the call returns {@code null}.
         */
        @NotNull(message = "newBubbleAcquireTimeoutMs is required")
        @Min(value = 0, message = "newBubbleAcquireTimeoutMs must be >= 0")
        @Max(value = 10000, message = "newBubbleAcquireTimeoutMs must be <= 10000")
        private Integer newBubbleAcquireTimeoutMs;

        /**
         * Default blocking-acquire timeout for non-streaming notifications and
         * keyboard sends. Past this window the call gives up and logs.
         */
        @NotNull(message = "defaultAcquireTimeoutMs is required")
        @Min(value = 0, message = "defaultAcquireTimeoutMs must be >= 0")
        @Max(value = 10000, message = "defaultAcquireTimeoutMs must be <= 10000")
        private Integer defaultAcquireTimeoutMs;

        /**
         * Total budget {@code editHtmlReliable} / {@code sendHtmlReliableAndGetId}
         * may spend across acquire+retry to deliver the final agent answer.
         * On exhaustion the FSM marks the context with
         * {@code TELEGRAM_DELIVERY_FAILED} so the handler can route to ERROR.
         */
        @NotNull(message = "finalEditMaxWaitMs is required")
        @Min(value = 0, message = "finalEditMaxWaitMs must be >= 0")
        @Max(value = 60000, message = "finalEditMaxWaitMs must be <= 60000")
        private Integer finalEditMaxWaitMs;
    }

    @PostConstruct
    public void parseWhitelistExceptions() {
        parseAccessConfig();
    }
    
    private void parseAccessConfig() {
        parseLevelConfig(access.getAdmin(), "admin");
        parseLevelConfig(access.getVip(), "vip");
        parseLevelConfig(access.getRegular(), "regular");
    }
    
    private void parseLevelConfig(AccessConfig.LevelConfig level, String levelName) {
        if (level == null) {
            return;
        }
        level.setIds(level.getIds() != null ? level.getIds() : new HashSet<>());
        level.setChannels(level.getChannels() != null ? level.getChannels() : new HashSet<>());
        level.setEmails(level.getEmails() != null ? level.getEmails() : new HashSet<>());
        log.info("Access config {}: ids={}, channels={}, emails={}", 
                levelName, level.getIds(), level.getChannels(), level.getEmails());
    }

    /**
     * Returns a combined set of all configured access channels across admin, vip and regular levels.
     * This is used by TelegramWhitelistService to check membership in any configured group/channel.
     */
    public Set<String> getAllAccessChannels() {
        Set<String> result = new HashSet<>();
        if (access != null) {
            addChannels(result, access.getAdmin());
            addChannels(result, access.getVip());
            addChannels(result, access.getRegular());
        }
        return result;
    }

    private void addChannels(Set<String> target, AccessConfig.LevelConfig level) {
        if (level != null && level.getChannels() != null) {
            target.addAll(level.getChannels());
        }
    }
} 
