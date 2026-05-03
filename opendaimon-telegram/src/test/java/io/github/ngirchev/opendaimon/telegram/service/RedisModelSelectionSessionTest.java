package io.github.ngirchev.opendaimon.telegram.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.model.ModelInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisModelSelectionSessionTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RedisModelSelectionSession session;

    @BeforeEach
    void setUp() {
        session = new RedisModelSelectionSession(redisTemplate, objectMapper);
    }

    @Test
    void shouldReturnCachedModelsFromRedis() throws JsonProcessingException {
        // Arrange
        List<ModelInfo> models = List.of(
                new ModelInfo("gpt-4", Set.of(ModelCapabilities.CHAT), "openai")
        );
        String json = objectMapper.writeValueAsString(models);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("model-selection:1")).thenReturn(json);
        AtomicInteger fetchCount = new AtomicInteger(0);

        // Act
        List<ModelInfo> result = session.getOrFetch(1L, () -> {
            fetchCount.incrementAndGet();
            return models;
        });

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name()).isEqualTo("gpt-4");
        assertThat(fetchCount.get()).isEqualTo(0);
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void shouldFetchAndStoreOnCacheMiss() throws JsonProcessingException {
        // Arrange
        List<ModelInfo> models = List.of(
                new ModelInfo("claude-3", Set.of(ModelCapabilities.CHAT), "anthropic")
        );
        String expectedJson = objectMapper.writeValueAsString(models);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("model-selection:1")).thenReturn(null);

        // Act
        List<ModelInfo> result = session.getOrFetch(1L, () -> models);

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name()).isEqualTo("claude-3");
        verify(valueOperations).set(eq("model-selection:1"), eq(expectedJson), eq(Duration.ofSeconds(60)));
    }

    @Test
    void shouldFallBackToFetcherWhenRedisUnavailable() {
        // Arrange
        List<ModelInfo> models = List.of(
                new ModelInfo("gpt-4", Set.of(ModelCapabilities.CHAT), "openai")
        );
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("Connection refused"));

        // Act
        List<ModelInfo> result = session.getOrFetch(1L, () -> models);

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name()).isEqualTo("gpt-4");
    }

    @Test
    void shouldDeleteKeyOnEvict() {
        // Act
        session.evict(1L);

        // Assert
        verify(redisTemplate).delete("model-selection:1");
    }

    @Test
    void shouldHandleRedisUnavailableOnEvict() {
        // Arrange
        doThrow(new RedisConnectionFailureException("Connection refused"))
                .when(redisTemplate).delete(anyString());

        // Act — should not throw
        session.evict(1L);

        // Assert — no exception propagated
        verify(redisTemplate).delete("model-selection:1");
    }
}
