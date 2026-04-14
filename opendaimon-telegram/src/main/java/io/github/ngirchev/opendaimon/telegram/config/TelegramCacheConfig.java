package io.github.ngirchev.opendaimon.telegram.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.telegram.service.ModelSelectionSession;
import io.github.ngirchev.opendaimon.telegram.service.RedisModelSelectionSession;
import io.github.ngirchev.opendaimon.common.config.FeatureToggle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Conditional configuration that wires the Redis-backed {@link ModelSelectionSession}
 * when the feature toggle is enabled.
 *
 * <p>When {@code open-daimon.telegram.cache.redis-enabled=true}, this configuration
 * creates a {@link RedisModelSelectionSession}. The bean takes precedence over the
 * in-memory fallback in {@link TelegramCommandHandlerConfig} via
 * {@code @ConditionalOnMissingBean}.
 */
@Configuration
@ConditionalOnProperty(name = FeatureToggle.Feature.TELEGRAM_CACHE_REDIS_ENABLED, havingValue = "true")
public class TelegramCacheConfig {

    @Bean
    public ModelSelectionSession redisModelSelectionSession(StringRedisTemplate redisTemplate,
                                                            ObjectMapper objectMapper) {
        return new RedisModelSelectionSession(redisTemplate, objectMapper);
    }
}
