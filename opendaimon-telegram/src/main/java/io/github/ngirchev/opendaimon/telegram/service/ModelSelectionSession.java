package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.common.ai.model.ModelInfo;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Per-user cache of the model list during model selection flow.
 *
 * <p>Avoids re-fetching models from the gateway on every callback
 * (category navigation, page turns). The cache is short-lived (TTL)
 * and evicted after model selection or cancel.
 */
public class ModelSelectionSession {

    private static final int TTL_SECONDS = 60;

    private final Map<Long, CachedModelList> userCache = new ConcurrentHashMap<>();

    public List<ModelInfo> getOrFetch(Long userId, Supplier<List<ModelInfo>> fetcher) {
        CachedModelList cached = userCache.get(userId);
        if (cached != null && cached.createdAt().isAfter(Instant.now().minusSeconds(TTL_SECONDS))) {
            return cached.models();
        }
        List<ModelInfo> models = fetcher.get();
        userCache.put(userId, new CachedModelList(List.copyOf(models), Instant.now()));
        return models;
    }

    public void evict(Long userId) {
        userCache.remove(userId);
    }

    private record CachedModelList(List<ModelInfo> models, Instant createdAt) {}
}
