package io.github.ngirchev.aibot.ai.springai.retry;

/**
 * Records success/failure of model calls for ranking (cooldown, ewma).
 * Implemented by model registry; calls for models without FREE capability are ignored in the implementation.
 */
public interface OpenRouterModelStatsRecorder {

    void recordSuccess(String modelId, long latencyMs);

    void recordFailure(String modelId, int status, long latencyMs);
}
