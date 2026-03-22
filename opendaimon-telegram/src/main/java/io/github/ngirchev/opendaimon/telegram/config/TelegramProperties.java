package io.github.ngirchev.opendaimon.telegram.config;

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
     * Access configuration for user priority levels.
     * Supports both environment variables and direct configuration.
     */
    private AccessConfig access = new AccessConfig();
    
    /**
     * Enable/disable settings for command handlers.
     */
    private Commands commands = new Commands();

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