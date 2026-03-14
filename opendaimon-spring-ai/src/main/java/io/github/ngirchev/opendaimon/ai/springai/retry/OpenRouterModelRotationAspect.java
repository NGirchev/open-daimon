package io.github.ngirchev.opendaimon.ai.springai.retry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIStreamResponse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;


@Slf4j
@Aspect
@RequiredArgsConstructor
public class OpenRouterModelRotationAspect {

    private final OpenRouterRotationRegistry registry;
    private final int maxAttempts;
    private static final int MAX_ERROR_BODY_CHARS = 2_000;

    @Around("@annotation(rotate)")
    public AIResponse rotateModels(ProceedingJoinPoint pjp, RotateOpenRouterModels rotate) throws Throwable {
        var args = pjp.getArgs();
        var modelConfig = requireNonNull(extractModelConfig(args));
        var command = extractCommand(args);
        var candidates = resolveCandidates(modelConfig, command);

        return rotate.stream()
                ? streamWithRetry(pjp, args, candidates, 0)
                : callWithRetry(pjp, args, candidates);
    }

    private AIResponse callWithRetry(
            ProceedingJoinPoint pjp,
            Object[] baseArgs,
            List<SpringAIModelConfig> candidates
    ) throws Throwable {
        RuntimeException last = null;
        for (SpringAIModelConfig candidate : candidates) {
            String modelId = candidate.getName();
            long startNs = System.nanoTime();
            try {
                Object[] args = replaceModelConfig(baseArgs, candidate);
                AIResponse result = (AIResponse) pjp.proceed(args);
                long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
                recordSuccessIfPossible(modelId, latencyMs);
                return result;
            } catch (Exception e) {
                long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
                recordFailureIfPossible(modelId, e, latencyMs);
                last = (e instanceof RuntimeException re) ? re : new RuntimeException(e);
                if (!isRetryable(e)) {
                    throw last;
                }
                log.warn("Spring AI call failed for model={}, retrying next if available. reason={}", modelId, e.getMessage());
            }
        }
        throw last != null ? last : new RuntimeException("No models available for retry");
    }

    private SpringAIStreamResponse streamWithRetry(
            ProceedingJoinPoint pjp,
            Object[] baseArgs,
            List<SpringAIModelConfig> candidates,
            int index
    ) {
        // TODO test approach without defer
//        try {
//            if (index >= candidates.size()) {
//                return new SpringAIStreamResponse(Flux.error(new RuntimeException("No models available for retry")));
//            }
//            SpringAIModelConfig candidate = candidates.get(index);
//            String modelId = candidate.getName();
//            Object[] args = replaceModelConfig(baseArgs, candidate);
//            SpringAIStreamResponse result = (SpringAIStreamResponse) pjp.proceed(args);
//            result.chatResponse().doOnNext()
//            return result;
//        } catch (Throwable e) {
//            // retry handling needed here
//            throw new RuntimeException(e);
//        }
        if (index >= candidates.size()) {
            log.warn("OpenRouter stream retry: no candidates available (index={}, totalCandidates={})", index, candidates.size());
            return new SpringAIStreamResponse(Flux.error(new RuntimeException("No models available for retry")));
        }
        SpringAIModelConfig candidate = candidates.get(index);
        String modelId = candidate.getName();
        Object[] args = replaceModelConfig(baseArgs, candidate);
        try {
            var response = (SpringAIStreamResponse) pjp.proceed(args);
            response.chatResponse().onErrorResume(nextError -> {
                int attempt = index + 1;
                int total = candidates.size();
                boolean retryable = isRetryable(nextError);
                log.warn("OpenRouter stream retry: error caught. model={}, retryable={}, attempt={} of {}, reason={}",
                        modelId, retryable, attempt, total, nextError.getMessage());
                if (!retryable) {
                    return Flux.error(nextError);
                }
                if (index + 1 >= candidates.size()) {
                    log.warn("OpenRouter stream retry: no more candidates after attempt {} of {} (current={}), cannot retry. reason={}",
                            attempt, total, modelId, nextError.getMessage());
                    return Flux.error(nextError);
                }
                String nextModel = candidates.get(index + 1).getName();
                log.warn("OpenRouter stream retry: switching from model={} to next candidate model={} (next attempt {} of {}). reason={}",
                        modelId, nextModel, index + 2, total, nextError.getMessage());
                return streamWithRetry(pjp, baseArgs, candidates, index + 1).chatResponse();
            });
            return response;
        } catch (Throwable t) {
            long latencyMs = 0L;
            boolean retryable = isRetryable(t);
            log.warn("OpenRouter stream retry: sync error. model={}, retryable={}, attempt={} of {}, reason={}",
                    modelId, retryable, index + 1, candidates.size(), t.getMessage());
            recordFailureIfPossible(modelId, t, latencyMs);
            if (retryable && index + 1 < candidates.size()) {
                String nextModel = candidates.get(index + 1).getName();
                log.warn("OpenRouter stream retry: switching from model={} to next candidate model={} (attempt {} of {}). reason={}",
                        modelId, nextModel, index + 2, candidates.size(), t.getMessage());
                return streamWithRetry(pjp, baseArgs, candidates, index + 1);
            }
            return new SpringAIStreamResponse(Flux.error(t));
        }
    }

    private SpringAIModelConfig extractModelConfig(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof SpringAIModelConfig config) {
                return config;
            }
        }
        return null;
    }

    private Object[] replaceModelConfig(Object[] args, SpringAIModelConfig modelConfig) {
        Object[] copy = args.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] instanceof SpringAIModelConfig) {
                copy[i] = modelConfig;
                break;
            }
        }
        return copy;
    }

    private static final String OPENROUTER_AUTO_MODEL = "openrouter/auto";

    private List<SpringAIModelConfig> resolveCandidates(SpringAIModelConfig modelConfig, AICommand command) {
        var capabilities = command.modelCapabilities();
        if (capabilities == null) {
            capabilities = Set.of();
        }
        String preferred = modelConfig != null ? modelConfig.getName() : null;
        List<SpringAIModelConfig> candidates = new ArrayList<>(registry.getCandidatesByCapabilities(capabilities, preferred));
        if (candidates.isEmpty() && modelConfig != null) {
            candidates = List.of(modelConfig);
        }
        // For single AUTO candidate (openrouter/auto) add CHAT fallback for retry on empty stream
        if (shouldAddChatFallback(capabilities, candidates)) {
            candidates = mergeWithChatCandidates(candidates, preferred);
            log.info("OpenRouter model rotation: added CHAT fallback candidates for retry (total={})", candidates.size());
        }
        if (maxAttempts >= 1 && candidates.size() > maxAttempts) {
            candidates = candidates.subList(0, maxAttempts);
        }
        log.info("OpenRouter model rotation candidates (maxAttempts={}): {}", maxAttempts,
                candidates.stream().map(SpringAIModelConfig::getName).toList());
        if (candidates.size() <= 1) {
            log.warn("OpenRouter model rotation: only {} candidate(s), retry on stream error will not switch model", candidates.size());
        }
        return candidates;
    }

    private boolean shouldAddChatFallback(Set<ModelCapabilities> capabilities, List<SpringAIModelConfig> candidates) {
        if (candidates.size() != 1) {
            return false;
        }
        return Set.of(ModelCapabilities.AUTO).equals(capabilities)
                || OPENROUTER_AUTO_MODEL.equals(candidates.get(0).getName());
    }

    private List<SpringAIModelConfig> mergeWithChatCandidates(List<SpringAIModelConfig> current, String preferred) {
        List<SpringAIModelConfig> chatCandidates = registry.getCandidatesByCapabilities(Set.of(ModelCapabilities.CHAT), preferred);
        Set<String> seen = new HashSet<>();
        List<SpringAIModelConfig> merged = new ArrayList<>();
        for (SpringAIModelConfig c : current) {
            if (seen.add(c.getName())) {
                merged.add(c);
            }
        }
        for (SpringAIModelConfig c : chatCandidates) {
            if (seen.add(c.getName())) {
                merged.add(c);
            }
        }
        return merged;
    }

    private AICommand extractCommand(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof AICommand cmd) {
                return cmd;
            }
        }
        throw new IllegalStateException("AICommand not found in aspect target method args");
    }

    private boolean isRetryable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof OpenRouterEmptyStreamException) {
                return true;
            }
        }
        WebClientResponseException w = findWebClientResponseException(error);
        if (w == null) {
            return true; // timeouts/transport errors: retry
        }
        int status = w.getStatusCode().value();
        if (isRetryableHttpStatus(status)) {
            return true;
        }
        return status == 400 && isRetryable400Body(w.getResponseBodyAsString());
    }

    private static boolean isRetryableHttpStatus(int status) {
        return status == 429 || status == 402 || status == 404 || (status >= 500 && status <= 599);
    }

    private static boolean isRetryable400Body(String body) {
        return body != null && body.contains("Conversation roles must alternate");
    }

    /**
     * Finds WebClientResponseException in cause chain (e.g. under Spring AI NonTransientAiException).
     */
    private static WebClientResponseException findWebClientResponseException(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof WebClientResponseException w) {
                return w;
            }
        }
        return null;
    }

    private void recordSuccessIfPossible(String modelId, long latencyMs) {
        registry.recordSuccess(modelId, latencyMs);
    }

    private void recordFailureIfPossible(String modelId, Throwable error, long latencyMs) {
        int status = 599;
        String responseBody = null;
        WebClientResponseException w = findWebClientResponseException(error);
        if (w != null) {
            status = w.getStatusCode().value();
            responseBody = truncate(w.getResponseBodyAsString());
        }
        registry.recordFailure(modelId, status, latencyMs);
        if (responseBody != null && status >= 400 && status <= 499) {
            log.warn("OpenRouter request failed. model={}, status={}, latencyMs={}, body={}",
                    modelId, status, latencyMs, responseBody);
        }
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
