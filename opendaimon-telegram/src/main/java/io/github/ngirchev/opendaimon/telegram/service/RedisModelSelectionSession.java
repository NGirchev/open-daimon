package io.github.ngirchev.opendaimon.telegram.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.common.ai.model.ModelInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * Redis-backed implementation of {@link ModelSelectionSession}.
 *
 * <p>Stores cached model lists in Redis with automatic TTL expiration.
 * Falls back to direct fetcher invocation if Redis is unavailable,
 * so a Redis outage does not break model selection.
 */
public class RedisModelSelectionSession implements ModelSelectionSession {

    private static final Logger log = LoggerFactory.getLogger(RedisModelSelectionSession.class);
    private static final String KEY_PREFIX = "model-selection:";
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final TypeReference<List<ModelInfo>> MODEL_LIST_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisModelSelectionSession(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ModelInfo> getOrFetch(Long userId, Supplier<List<ModelInfo>> fetcher) {
        String key = KEY_PREFIX + userId;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, MODEL_LIST_TYPE);
            }
            List<ModelInfo> models = fetcher.get();
            String json = objectMapper.writeValueAsString(models);
            redisTemplate.opsForValue().set(key, json, TTL);
            return models;
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable, falling back to direct fetch for userId={}", userId, e);
            return fetcher.get();
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize/deserialize model list for userId={}", userId, e);
            return fetcher.get();
        }
    }

    @Override
    public void evict(Long userId) {
        String key = KEY_PREFIX + userId;
        try {
            redisTemplate.delete(key);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable, skipping evict for userId={}", userId, e);
        }
    }
}
