package io.github.ngirchev.opendaimon.it.telegram;

import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.model.ModelInfo;
import io.github.ngirchev.opendaimon.it.ITTestConfiguration;
import io.github.ngirchev.opendaimon.telegram.config.TelegramCacheConfig;
import io.github.ngirchev.opendaimon.telegram.service.ModelSelectionSession;
import io.github.ngirchev.opendaimon.telegram.service.RedisModelSelectionSession;
import io.github.ngirchev.opendaimon.test.AbstractContainerIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that verifies Redis-backed model selection session
 * works correctly with a real Redis instance (via Testcontainers).
 */
@SpringBootTest(classes = ITTestConfiguration.class)
@ActiveProfiles("test")
@Import(TelegramCacheConfig.class)
@TestPropertySource(properties = {
        "open-daimon.telegram.cache.redis-enabled=true",
        "open-daimon.telegram.enabled=false"
})
class RedisModelSelectionSessionIT extends AbstractContainerIT {

    @Autowired
    private ModelSelectionSession modelSelectionSession;

    @Test
    @DisplayName("Redis session bean should be wired when redis-enabled=true")
    void shouldUseRedisImplementation() {
        assertThat(modelSelectionSession).isInstanceOf(RedisModelSelectionSession.class);
    }

    @Test
    @DisplayName("getOrFetch should cache models in Redis and return on second call")
    void shouldCacheModelsInRedis() {
        // Arrange
        AtomicInteger fetchCount = new AtomicInteger(0);
        List<ModelInfo> models = List.of(
                new ModelInfo("gpt-4", Set.of(ModelCapabilities.CHAT), "openai"),
                new ModelInfo("claude-3", Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION), "anthropic")
        );

        // Act
        List<ModelInfo> first = modelSelectionSession.getOrFetch(100L, () -> {
            fetchCount.incrementAndGet();
            return models;
        });
        List<ModelInfo> second = modelSelectionSession.getOrFetch(100L, () -> {
            fetchCount.incrementAndGet();
            return models;
        });

        // Assert
        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);
        assertThat(first).isEqualTo(second);
        assertThat(fetchCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("evict should remove cached models so next call re-fetches")
    void shouldEvictCachedModels() {
        // Arrange
        AtomicInteger fetchCount = new AtomicInteger(0);
        List<ModelInfo> models = List.of(
                new ModelInfo("gpt-4", Set.of(ModelCapabilities.CHAT), "openai")
        );

        // Act
        modelSelectionSession.getOrFetch(200L, () -> {
            fetchCount.incrementAndGet();
            return models;
        });
        modelSelectionSession.evict(200L);
        modelSelectionSession.getOrFetch(200L, () -> {
            fetchCount.incrementAndGet();
            return models;
        });

        // Assert
        assertThat(fetchCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Different users should have isolated caches")
    void shouldIsolateUserCaches() {
        // Arrange
        List<ModelInfo> modelsUser1 = List.of(
                new ModelInfo("gpt-4", Set.of(ModelCapabilities.CHAT), "openai")
        );
        List<ModelInfo> modelsUser2 = List.of(
                new ModelInfo("claude-3", Set.of(ModelCapabilities.CHAT), "anthropic")
        );

        // Act
        List<ModelInfo> result1 = modelSelectionSession.getOrFetch(301L, () -> modelsUser1);
        List<ModelInfo> result2 = modelSelectionSession.getOrFetch(302L, () -> modelsUser2);

        // Assert
        assertThat(result1.getFirst().name()).isEqualTo("gpt-4");
        assertThat(result2.getFirst().name()).isEqualTo("claude-3");
    }
}
