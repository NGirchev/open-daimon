package io.github.ngirchev.opendaimon.ai.springai.retry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.StringUtils;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;

import java.time.Duration;
import java.util.List;

/**
 * Settings for OpenRouter model list refresh and ranking.
 * Allowed free models are configured via whitelist (per-role, multiple entries).
 * Blocked models are configured via blacklist (global, applied first).
 *
 * Important: no defaults in code, everything is set in application.yml.
 */
@ConfigurationProperties(prefix = "open-daimon.ai.spring-ai.openrouter-auto-rotation.models")
@Validated
@Getter
@Setter
public class OpenRouterModelsProperties {

    /**
     * Enable/disable background refresh of OpenRouter model list and ranking.
     */
    @NotNull(message = "enabled is required")
    private Boolean enabled;

    @Valid
    @NestedConfigurationProperty
    private Api api;

    private Duration refreshInterval;

    private Duration refreshInitialDelay;

    @Valid
    @NestedConfigurationProperty
    private Ranking ranking;

    /**
     * Per-role allowlists. Multiple entries are supported; a model is allowed if it matches
     * at least one whitelist entry. If the list is null or empty — all API free models pass.
     */
    @Valid
    private List<Whitelist> whitelist;

    /**
     * Global blacklist applied before any whitelist. Models matched here are never added.
     */
    @Valid
    @NestedConfigurationProperty
    private Blacklist blacklist;

    @Getter
    @Setter
    public static class Api {
        /**
         * OpenRouter API key.
         */
        private String key;

        /**
         * OpenRouter API base URL. Example: https://openrouter.ai/api
         * Full URL up to /v1/chat/completions is also allowed and will be normalized.
         */
        private String url;
    }

    @Getter
    @Setter
    public static class Ranking {
        /**
         * Enable ranking by errors/latency based on real requests.
         */
        @NotNull(message = "ranking.enabled is required")
        private Boolean enabled;

        /**
         * Maximum number of attempts (models) on retry for a single request.
         */
        @NotNull(message = "ranking.retryMaxAttempts is required")
        private Integer retryMaxAttempts;

        /**
         * EWMA alpha for latency (0..1). Closer to 1 = faster reaction to changes.
         */
        @NotNull(message = "ranking.latencyEwmaAlpha is required")
        private Double latencyEwmaAlpha;

        /**
         * Cooldown after 429 (rate limit/overload).
         */
        @NotNull(message = "ranking.cooldown429 is required")
        private Duration cooldown429;

        /**
         * Cooldown after 5xx (provider/routing errors).
         */
        @NotNull(message = "ranking.cooldown5xx is required")
        private Duration cooldown5xx;

        /**
         * Cooldown after 404 (model unavailable due to guardrail/data-policy restrictions).
         * Optional — if not set, 404 is not cooled down (only score penalty applies).
         */
        private Duration cooldown404;
    }

    @Getter
    @Setter
    public static class Whitelist {
        /**
         * Roles that can use models matched by this whitelist entry.
         * Null or empty means all roles are allowed.
         */
        private List<UserPriority> roles;

        /**
         * Exact model IDs allowed by this whitelist entry.
         */
        private List<String> includeModelIds;

    }

    @Getter
    @Setter
    public static class Blacklist {
        /**
         * Exact model IDs that must never be added, regardless of whitelist.
         */
        private List<String> excludeModelIds;

        /**
         * Models whose ID contains any of these fragments are excluded regardless of whitelist.
         */
        private List<String> excludeContains;
    }

    @AssertTrue(message = "When enabled=true, api.key, api.url, refresh-initial-delay and refresh-interval (both > 0) must be set")
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

    @AssertTrue(message = "When enabled=true and ranking.enabled=true, ranking.* must be set and valid")
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
