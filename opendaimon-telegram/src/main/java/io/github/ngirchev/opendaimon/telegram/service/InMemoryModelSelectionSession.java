package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.common.ai.model.ModelInfo;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-memory implementation of {@link ModelSelectionSession}.
 *
 * <p>Uses a {@link ConcurrentHashMap} with time-based TTL.
 * Suitable for single-instance deployments where distributed
 * state sharing is not required.
 */
public class InMemoryModelSelectionSession implements ModelSelectionSession {

    private static final int TTL_SECONDS = 60;

    private final Map<Long, CachedModelList> userCache = new ConcurrentHashMap<>();

    @Override
    public List<ModelInfo> getOrFetch(Long userId, Supplier<List<ModelInfo>> fetcher) {
        CachedModelList cached = userCache.get(userId);
        if (cached != null && cached.createdAt().isAfter(Instant.now().minusSeconds(TTL_SECONDS))) {
            return cached.models();
        }
        List<ModelInfo> models = fetcher.get();
        userCache.put(userId, new CachedModelList(List.copyOf(models), Instant.now()));
        return models;
    }

    @Override
    public void evict(Long userId) {
        userCache.remove(userId);
    }

    private record CachedModelList(List<ModelInfo> models, Instant createdAt) {}
}
