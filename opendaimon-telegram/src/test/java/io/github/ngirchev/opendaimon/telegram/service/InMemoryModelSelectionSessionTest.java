package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.model.ModelInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryModelSelectionSessionTest {

    private InMemoryModelSelectionSession session;

    @BeforeEach
    void setUp() {
        session = new InMemoryModelSelectionSession();
    }

    @Test
    void shouldReturnCachedModelsOnSecondCall() {
        // Arrange
        AtomicInteger fetchCount = new AtomicInteger(0);
        List<ModelInfo> models = List.of(
                new ModelInfo("gpt-4", Set.of(ModelCapabilities.CHAT), "openai")
        );

        // Act
        List<ModelInfo> first = session.getOrFetch(1L, () -> {
            fetchCount.incrementAndGet();
            return models;
        });
        List<ModelInfo> second = session.getOrFetch(1L, () -> {
            fetchCount.incrementAndGet();
            return models;
        });

        // Assert
        assertThat(first).isEqualTo(second);
        assertThat(fetchCount.get()).isEqualTo(1);
    }

    @Test
    void shouldIsolateUserCaches() {
        // Arrange
        List<ModelInfo> modelsUser1 = List.of(
                new ModelInfo("gpt-4", Set.of(ModelCapabilities.CHAT), "openai")
        );
        List<ModelInfo> modelsUser2 = List.of(
                new ModelInfo("claude-3", Set.of(ModelCapabilities.CHAT), "anthropic")
        );

        // Act
        List<ModelInfo> result1 = session.getOrFetch(1L, () -> modelsUser1);
        List<ModelInfo> result2 = session.getOrFetch(2L, () -> modelsUser2);

        // Assert
        assertThat(result1).hasSize(1);
        assertThat(result1.getFirst().name()).isEqualTo("gpt-4");
        assertThat(result2).hasSize(1);
        assertThat(result2.getFirst().name()).isEqualTo("claude-3");
    }

    @Test
    void shouldEvictCache() {
        // Arrange
        AtomicInteger fetchCount = new AtomicInteger(0);
        List<ModelInfo> models = List.of(
                new ModelInfo("gpt-4", Set.of(ModelCapabilities.CHAT), "openai")
        );

        // Act
        session.getOrFetch(1L, () -> {
            fetchCount.incrementAndGet();
            return models;
        });
        session.evict(1L);
        session.getOrFetch(1L, () -> {
            fetchCount.incrementAndGet();
            return models;
        });

        // Assert
        assertThat(fetchCount.get()).isEqualTo(2);
    }

    @Test
    void shouldReturnDefensiveCopy() {
        // Arrange
        List<ModelInfo> models = List.of(
                new ModelInfo("gpt-4", Set.of(ModelCapabilities.CHAT), "openai")
        );

        // Act
        List<ModelInfo> result = session.getOrFetch(1L, () -> models);

        // Assert — returned list should be immutable (List.copyOf)
        assertThat(result).isUnmodifiable();
    }

    @Test
    void shouldInvokeFetcherOnceUnderConcurrentRequestsForSameUser() throws InterruptedException {
        // Reproducer for TD-future-A race: under non-atomic get()+put(), two threads observing
        // the same cache miss would both invoke the (slow) fetcher. Atomic compute() single-flights it.
        AtomicInteger fetcherCalls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<ModelInfo> models = List.of(new ModelInfo("gpt-4", Set.of(ModelCapabilities.CHAT), "openai"));
        Supplier<List<ModelInfo>> slowFetcher = () -> {
            fetcherCalls.incrementAndGet();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return models;
        };
        Runnable task = () -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            session.getOrFetch(42L, slowFetcher);
            done.countDown();
        };
        new Thread(task, "concurrent-fetcher-1").start();
        new Thread(task, "concurrent-fetcher-2").start();

        start.countDown();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(fetcherCalls.get()).isEqualTo(1);
    }
}
