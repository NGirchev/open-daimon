package io.github.ngirchev.aibot.telegram.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;


@Slf4j
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "ai-bot.telegram")
public class TelegramProperties {
    
    @NotBlank(message = "Bot token cannot be blank")
    private String token;
    
    @NotBlank(message = "Bot username cannot be blank")
    private String username;
    
    /**
     * Comma-separated Telegram user IDs (e.g. "350001752,123456789").
     * Parsed into Set<Long> on initialization.
     */
    private String whitelistExceptions;
    
    /**
     * Set of Telegram user IDs to be automatically added to whitelist at application startup.
     * Parsed from whitelistExceptions string.
     */
    private Set<Long> whitelistExceptionsSet = new HashSet<>();
    
    /**
     * Comma-separated Telegram group/channel IDs (e.g. "-1000000000000,@mygroup").
     * Members of these groups/channels get access to the bot.
     * If a user is not in whitelist but is a member of one of these groups/channels,
     * they are automatically added to whitelist.
     * Can be numeric ID (e.g. -1000000000000) or username (e.g. @mygroup).
     * Parsed into Set<String> on initialization.
     */
    private String whitelistChannelIdExceptions;
    
    /**
     * Set of Telegram group/channel IDs whose members get access to the bot.
     * Parsed from whitelistChannelIdExceptions string.
     */
    private Set<String> whitelistChannelIdExceptionsSet = new HashSet<>();
    
    /**
     * Enable/disable settings for command handlers.
     */
    private Commands commands = new Commands();

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
    }
    
    @PostConstruct
    public void parseWhitelistExceptions() {
        if (whitelistExceptions == null || whitelistExceptions.trim().isEmpty()) {
            whitelistExceptionsSet = new HashSet<>();
            log.info("whitelist-exceptions is empty or null, exception list will be empty");
        } else {
            try {
                whitelistExceptionsSet = Arrays.stream(whitelistExceptions.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Long::parseLong)
                        .collect(Collectors.toSet());
                log.info("Parsed whitelist-exceptions: '{}' -> {}", whitelistExceptions, whitelistExceptionsSet);
            } catch (NumberFormatException e) {
                log.warn("Failed to parse whitelist-exceptions '{}': {}. Exception list will be empty", 
                        whitelistExceptions, e.getMessage());
                whitelistExceptionsSet = new HashSet<>();
            }
        }
        
        if (whitelistChannelIdExceptions == null || whitelistChannelIdExceptions.trim().isEmpty()) {
            whitelistChannelIdExceptionsSet = new HashSet<>();
            log.info("whitelist-channel-id-exceptions is empty or null, channel list will be empty");
        } else {
            whitelistChannelIdExceptionsSet = Arrays.stream(whitelistChannelIdExceptions.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
            log.info("Parsed whitelist-channel-id-exceptions: '{}' -> {}", 
                    whitelistChannelIdExceptions, whitelistChannelIdExceptionsSet);
        }
    }
} 