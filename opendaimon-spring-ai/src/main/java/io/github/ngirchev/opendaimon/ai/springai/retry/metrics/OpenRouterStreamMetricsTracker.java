package io.github.ngirchev.opendaimon.ai.springai.retry.metrics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import io.github.ngirchev.opendaimon.ai.springai.retry.OpenRouterModelStatsRecorder;

/**
 * Explicitly wraps stream so model metrics are recorded without AOP.
 * Uses {@link OpenRouterModelStatsRecorder} (registry); recording for FREE capability models only is done inside the registry.
 */
@Slf4j
@RequiredArgsConstructor
public class OpenRouterStreamMetricsTracker {

    private final ObjectProvider<OpenRouterModelStatsRecorder> openRouterModelStatsRecorderProvider;
    private static final int MAX_ERROR_BODY_CHARS = 4_000;

    public <T> Flux<T> track(String modelId, Flux<T> source) {
        OpenRouterModelStatsRecorder recorder = openRouterModelStatsRecorderProvider.getIfAvailable();
        if (recorder == null || modelId == null) {
            return source;
        }

        return Flux.defer(() -> {
            long startNs = System.nanoTime();
            return source
                    .doOnComplete(() -> {
                        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
                        recorder.recordSuccess(modelId, durationMs);
                        log.info("OpenRouter stream completed. model={}, durationMs={}", modelId, durationMs);
                    })
                    .doOnError(error -> {
                        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
                        int status = 599;
                        String responseBody = null;
                        if (error instanceof WebClientResponseException w) {
                            status = w.getStatusCode().value();
                            responseBody = truncate(w.getResponseBodyAsString());
                        }
                        recorder.recordFailure(modelId, status, durationMs);
                        if (responseBody != null) {
                            log.warn("OpenRouter stream failed. model={}, status={}, durationMs={}, body={}",
                                    modelId, status, durationMs, responseBody);
                        } else {
                            log.warn("OpenRouter stream failed. model={}, status={}, durationMs={}", modelId, status, durationMs);
                        }
                    });
        });
    }

    private String truncate(String body) {
        if (body == null) {
            return null;
        }
        if (body.length() <= MAX_ERROR_BODY_CHARS) {
            return body;
        }
        return body.substring(0, MAX_ERROR_BODY_CHARS) + "...(truncated)";
    }
}
