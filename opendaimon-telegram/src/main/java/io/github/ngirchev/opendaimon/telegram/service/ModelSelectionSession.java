package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.common.ai.model.ModelInfo;

import java.util.List;
import java.util.function.Supplier;

/**
 * Per-user cache of the model list during model selection flow.
 *
 * <p>Avoids re-fetching models from the gateway on every callback
 * (category navigation, page turns). The cache is short-lived (TTL)
 * and evicted after model selection or cancel.
 */
public interface ModelSelectionSession {

    List<ModelInfo> getOrFetch(Long userId, Supplier<List<ModelInfo>> fetcher);

    void evict(Long userId);
}
