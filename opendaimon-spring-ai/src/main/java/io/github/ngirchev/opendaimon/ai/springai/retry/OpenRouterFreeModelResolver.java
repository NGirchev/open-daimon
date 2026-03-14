package io.github.ngirchev.opendaimon.ai.springai.retry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves "openrouter/auto" to a concrete free model using OpenRouter Models API (/v1/models).
 * Free model selection logic follows {@code OpenRouterMaxPriceExample}.
 */
@Slf4j
@RequiredArgsConstructor
public class OpenRouterFreeModelResolver {

    public static final String OPENROUTER_AUTO = "openrouter/auto";

    private static final String PRICING_PROMPT = "prompt";
    private static final String PRICING_COMPLETION = "completion";
    private static final String FREE_SUFFIX = ":free";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final OpenRouterModelsProperties openRouterModelsProperties;

    private final AtomicReference<List<String>> cachedFreeOpenRouterModels = new AtomicReference<>();
    private final AtomicLong lastRefreshAtEpochMs = new AtomicLong(0);

    private final Map<String, ModelStats> statsByModelId = new ConcurrentHashMap<>();
    private final Map<String, ModelInfo> infoByModelId = new ConcurrentHashMap<>();

    public String resolveIfAuto(String modelName) {
        if (!OPENROUTER_AUTO.equals(modelName)) {
            return modelName;
        }
        String selected = selectBestFreeOpenRouterModelId();
        return selected != null ? selected : modelName;
    }

    public void refresh() {
        List<String> loaded = loadFreeOpenRouterModelIds();
        cachedFreeOpenRouterModels.set(loaded != null ? List.copyOf(loaded) : null);
        lastRefreshAtEpochMs.set(System.currentTimeMillis());
    }

    public List<String> getCachedFreeModelIds() {
        List<String> cached = cachedFreeOpenRouterModels.get();
        return cached != null ? cached : Collections.emptyList();
    }

    public long getLastRefreshAtEpochMs() {
        return lastRefreshAtEpochMs.get();
    }

    /**
     * List of candidates for production request in preference order.
     * If ranking is disabled — returns one element (best by stable sort).
     */
    public List<String> candidatesForAutoRequest() {
        return candidatesForAutoRequest(Collections.emptySet());
    }

    /**
     * Generic method: if {@code openrouter/auto} — returns ranked free candidates.
     * If a concrete free model — returns candidate list with requested model first (if allowed).
     * Otherwise — returns requested model as single candidate.
     */
    public List<String> candidatesForModel(String requestedModelId, Set<ModelCapabilities> requiredModelCapabilities) {
        if (!StringUtils.hasText(requestedModelId)) {
            return Collections.emptyList();
        }
        if (OPENROUTER_AUTO.equals(requestedModelId)) {
            return candidatesForAutoRequest(requiredModelCapabilities);
        }
        if (requestedModelId.contains(FREE_SUFFIX)) {
            List<String> candidates = candidatesForAutoRequest(requiredModelCapabilities);
            if (candidates.isEmpty()) {
                return Collections.emptyList();
            }
            if (!candidates.contains(requestedModelId)) {
                return candidates;
            }
            ArrayList<String> reordered = new ArrayList<>(candidates.size());
            reordered.add(requestedModelId);
            for (String id : candidates) {
                if (!requestedModelId.equals(id)) {
                    reordered.add(id);
                }
            }
            return reordered;
        }
        return List.of(requestedModelId);
    }

    /**
     * List of candidates for live request with capability requirements.
     * Currently only {@link ModelCapabilities#TOOL_CALLING} is enforced.
     */
    public List<String> candidatesForAutoRequest(Set<ModelCapabilities> requiredModelCapabilities) {
        List<String> cached = getCachedFreeModelIds();
        if (cached.isEmpty()) {
            // lazy first run
            refresh();
            cached = getCachedFreeModelIds();
        }
        if (cached.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> free = cached.stream()
                .filter(id -> id != null && id.contains(FREE_SUFFIX))
                .toList();
        if (free.isEmpty()) {
            return List.of(cached.getFirst());
        }

        List<String> filteredByProps = applyPropertyFilters(free);
        if (filteredByProps.isEmpty()) {
            log.warn("OpenRouter free model property filters produced empty candidates, fallback to all free models");
            filteredByProps = free;
        }

        var ranking = openRouterModelsProperties.getRanking();
        boolean rankingEnabled = ranking.getEnabled();

        List<String> eligible = applyCapabilityFilters(filteredByProps, requiredModelCapabilities);
        if (eligible.isEmpty() && requiredModelCapabilities != null && !requiredModelCapabilities.isEmpty()) {
            log.warn("OpenRouter free model filter produced empty candidates for requiredModelTypes={}, fallback to all free models",
                    requiredModelCapabilities);
            eligible = filteredByProps;
        }

        int maxAttempts = 1;
        if (rankingEnabled) {
            maxAttempts = ranking.getRetryMaxAttempts();
        }
        maxAttempts = Math.min(maxAttempts, eligible.size());

        if (!rankingEnabled) {
            List<String> selected = eligible.subList(0, maxAttempts);
            log.info("OpenRouter free model ranking disabled. candidates={}", selected);
            return selected;
        }

        long now = System.currentTimeMillis();
        Comparator<String> candidateComparator = candidateComparator(now);
        List<String> selected = eligible.stream()
                .sorted(candidateComparator)
                .limit(maxAttempts)
                .toList();
        logRankingDecision(selected, now);
        return selected;
    }

    public void recordSuccess(String modelId, long latencyMs) {
        if (!StringUtils.hasText(modelId)) {
            return;
        }
        OpenRouterModelsProperties.Ranking ranking = openRouterModelsProperties.getRanking();
        double alpha = (ranking != null && ranking.getLatencyEwmaAlpha() != null) ? ranking.getLatencyEwmaAlpha() : 0.2d;

        ModelStats stats = statsByModelId.computeIfAbsent(modelId, k -> new ModelStats());
        long now = System.currentTimeMillis();
        double scoreBefore = score(modelId, now);
        double ewmaBefore = stats.ewmaLatencyMs;
        long cooldownBefore = stats.cooldownUntilEpochMs;
        stats.lastStatus = 200;
        stats.ewmaLatencyMs = ewma(stats.ewmaLatencyMs, latencyMs, alpha);
        stats.cooldownUntilEpochMs = 0;
        stats.lastUpdatedAtEpochMs = System.currentTimeMillis();

        double scoreAfter = score(modelId, System.currentTimeMillis());
        log.info(
                "OpenRouter model stats updated (success). model={}, latencyMs={}, ewmaMs={}→{}, status={}→{}, cooldownUntil={}→{}, score={}→{}",
                modelId,
                latencyMs,
                formatDouble(ewmaBefore),
                formatDouble(stats.ewmaLatencyMs),
                200,
                stats.lastStatus,
                cooldownBefore == 0 ? "-" : cooldownBefore,
                stats.cooldownUntilEpochMs == 0 ? "-" : stats.cooldownUntilEpochMs,
                formatDouble(scoreBefore),
                formatDouble(scoreAfter)
        );
    }

    public void recordFailure(String modelId, int status, long latencyMs) {
        if (!StringUtils.hasText(modelId)) {
            return;
        }
        OpenRouterModelsProperties.Ranking ranking = openRouterModelsProperties.getRanking();
        double alpha = (ranking != null && ranking.getLatencyEwmaAlpha() != null) ? ranking.getLatencyEwmaAlpha() : 0.2d;

        ModelStats stats = statsByModelId.computeIfAbsent(modelId, k -> new ModelStats());
        long now = System.currentTimeMillis();
        double scoreBefore = score(modelId, now);
        double ewmaBefore = stats.ewmaLatencyMs;
        long cooldownBefore = stats.cooldownUntilEpochMs;
        stats.lastStatus = status;
        stats.ewmaLatencyMs = ewma(stats.ewmaLatencyMs, latencyMs, alpha);
        stats.lastUpdatedAtEpochMs = System.currentTimeMillis();

        if (ranking == null || !Boolean.TRUE.equals(ranking.getEnabled())) {
            double scoreAfter = score(modelId, System.currentTimeMillis());
            log.info(
                    "OpenRouter model stats updated (failure, ranking disabled). model={}, status={}, latencyMs={}, ewmaMs={}→{}, score={}→{}",
                    modelId,
                    status,
                    latencyMs,
                    formatDouble(ewmaBefore),
                    formatDouble(stats.ewmaLatencyMs),
                    formatDouble(scoreBefore),
                    formatDouble(scoreAfter)
            );
            return;
        }
        long cooldownMs = 0;
        if (status == 429) {
            cooldownMs = ranking.getCooldown429().toMillis();
        } else if (status >= 500 && status <= 599) {
            cooldownMs = ranking.getCooldown5xx().toMillis();
        }
        if (cooldownMs > 0) {
            stats.cooldownUntilEpochMs = System.currentTimeMillis() + cooldownMs;
        }

        double scoreAfter = score(modelId, System.currentTimeMillis());
        log.info(
                "OpenRouter model stats updated (failure). model={}, status={}, latencyMs={}, ewmaMs={}→{}, cooldownUntil={}→{}, score={}→{}",
                modelId,
                status,
                latencyMs,
                formatDouble(ewmaBefore),
                formatDouble(stats.ewmaLatencyMs),
                cooldownBefore == 0 ? "-" : cooldownBefore,
                stats.cooldownUntilEpochMs == 0 ? "-" : stats.cooldownUntilEpochMs,
                formatDouble(scoreBefore),
                formatDouble(scoreAfter)
        );
    }

    private String selectBestFreeOpenRouterModelId() {
        try {
            List<String> cached = cachedFreeOpenRouterModels.get();
            if (cached == null) {
                cached = loadFreeOpenRouterModelIds();
                if (cached != null) {
                    cachedFreeOpenRouterModels.set(List.copyOf(cached));
                    lastRefreshAtEpochMs.set(System.currentTimeMillis());
                }
            }
            if (cached == null || cached.isEmpty()) {
                return null;
            }

            OpenRouterModelsProperties.Ranking ranking = openRouterModelsProperties.getRanking();
            boolean rankingEnabled = Boolean.TRUE.equals(openRouterModelsProperties.getEnabled())
                    && ranking != null
                    && Boolean.TRUE.equals(ranking.getEnabled());

            List<String> free = cached.stream()
                    .filter(id -> id != null && id.contains(FREE_SUFFIX))
                    .toList();
            if (free.isEmpty()) {
                return cached.getFirst();
            }
            List<String> filteredByProps = applyPropertyFilters(free);
            if (filteredByProps.isEmpty()) {
                filteredByProps = free;
            }

            // If ranking disabled — keep previous behaviour (stable choice).
            if (!rankingEnabled) {
                return filteredByProps.getFirst();
            }

            long now = System.currentTimeMillis();
            return filteredByProps.stream()
                    .max(candidateComparator(now))
                    .orElse(cached.getFirst());
        } catch (Exception e) {
            log.warn("Failed to select free OpenRouter model (will fallback to openrouter/auto). reason={}", e.getMessage(), e);
            return null;
        }
    }

    private Comparator<String> candidateComparator(long nowEpochMs) {
        return Comparator.<String>comparingDouble(id -> score(id, nowEpochMs)).reversed()
                // Prefer models that already succeeded in this process
                .thenComparing((String id) -> {
                    ModelStats stats = statsByModelId.get(id);
                    return stats != null && stats.lastStatus == 200;
                }, Comparator.reverseOrder())
                // Prefer tools-capable models as a general proxy for "chat" capability quality
                .thenComparing((String id) -> infoByModelId.getOrDefault(id, ModelInfo.UNKNOWN).capabilities().contains(ModelCapabilities.TOOL_CALLING), Comparator.reverseOrder())
                .thenComparing(Comparator.naturalOrder());
    }

    private double score(String modelId, long nowEpochMs) {
        ModelStats stats = statsByModelId.get(modelId);
        if (stats == null) {
            // No metrics — neutral score
            return 50.0d;
        }
        if (stats.cooldownUntilEpochMs > nowEpochMs) {
            // In cooldown — effectively exclude
            return -10_000.0d;
        }
        double base = 100.0d;
        // Lower latency is better
        base -= (stats.ewmaLatencyMs / 200.0d);
        // Penalties for recent errors
        if (stats.lastStatus == 429) {
            base -= 30.0d;
        } else if (stats.lastStatus >= 400 && stats.lastStatus <= 499) {
            // 4xx usually means input/format incompatibility with this model (e.g. strict roles validation),
            // so such models should rank below "unknown" models.
            base -= 120.0d;
        } else if (stats.lastStatus >= 500 && stats.lastStatus <= 599) {
            base -= 50.0d;
        }
        return base;
    }
    private double ewma(double current, long sampleMs, double alpha) {
        if (current <= 0) {
            return sampleMs;
        }
        return current * (1.0d - alpha) + sampleMs * alpha;
    }

    private List<String> loadFreeOpenRouterModelIds() {
        OpenRouterModelsProperties.Api api = openRouterModelsProperties.getApi();
        if (api == null || !StringUtils.hasText(api.getKey()) || !StringUtils.hasText(api.getUrl())) {
            log.warn("OpenRouter free-model discovery is skipped: baseUrl/apiKey is empty");
            return Collections.emptyList();
        }

        OpenRouterClientConfig cfg = OpenRouterClientConfig.fromChatCompletionsUrl(api.getUrl(), api.getKey());
        if (!StringUtils.hasText(cfg.apiKey()) || !StringUtils.hasText(cfg.baseUrl())) {
            log.warn("OpenRouter free-model discovery is skipped: baseUrl/apiKey is empty");
            return Collections.emptyList();
        }

        String modelsUrl = cfg.baseUrl() + "/v1/models";
        JsonNode modelsJson = fetchJson(modelsUrl, cfg.apiKey());

        List<String> free = new ArrayList<>();
        for (JsonNode m : modelsJson.path("data")) {
            String id = m.path("id").asText(null);
            if (!StringUtils.hasText(id)) {
                continue;
            }
            JsonNode pricing = m.path("pricing");
            if (pricing.isMissingNode() || pricing.isNull()) {
                continue;
            }
            if (isZero(pricing.path(PRICING_PROMPT)) && isZero(pricing.path(PRICING_COMPLETION))) {
                free.add(id);
                infoByModelId.put(id, extractModelInfo(m));
            }
        }

        if (free.isEmpty()) {
            log.warn("OpenRouter free-model discovery: no free models found (pricing.prompt & pricing.completion == 0)");
            return Collections.emptyList();
        }

        free.sort(Comparator.comparing((String s) -> !s.contains(FREE_SUFFIX)).thenComparing(Comparator.naturalOrder()));
        log.debug("OpenRouter free-model discovery (resolver): loaded {} free models. Final registry is in SpringAIModelRegistry logs.", free.size());
        return free;
    }

    private JsonNode fetchJson(String url, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

        String body = response.getBody();
        try {
            return objectMapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON from " + url, e);
        }
    }

    private boolean isZero(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return false;
        }
        String text = n.asText();
        if (!StringUtils.hasText(text)) {
            return false;
        }
        try {
            return Double.parseDouble(text.trim()) == 0.0d;
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<String> applyCapabilityFilters(List<String> modelIds, Set<ModelCapabilities> requiredModelCapabilities) {
        if (modelIds == null || modelIds.isEmpty()) {
            return Collections.emptyList();
        }
        if (requiredModelCapabilities == null || requiredModelCapabilities.isEmpty()) {
            return modelIds;
        }
        return modelIds.stream()
                .filter(id -> {
                    Set<ModelCapabilities> modelCaps = infoByModelId.getOrDefault(id, ModelInfo.UNKNOWN).capabilities();
                    return modelCaps.containsAll(requiredModelCapabilities);
                })
                .toList();
    }

    private List<String> applyPropertyFilters(List<String> modelIds) {
        if (modelIds == null || modelIds.isEmpty()) {
            return Collections.emptyList();
        }
        OpenRouterModelsProperties.Filters filters = openRouterModelsProperties.getFilters();
        if (filters == null) {
            return modelIds;
        }

        List<String> result = modelIds;

        if (filters.getIncludeModelIds() != null && !filters.getIncludeModelIds().isEmpty()) {
            HashSet<String> allow = new HashSet<>(filters.getIncludeModelIds());
            result = result.stream().filter(allow::contains).toList();
        }

        if (filters.getIncludeContains() != null && !filters.getIncludeContains().isEmpty()) {
            List<String> parts = filters.getIncludeContains();
            result = result.stream()
                    .filter(id -> parts.stream().anyMatch(id::contains))
                    .toList();
        }

        if (filters.getExcludeModelIds() != null && !filters.getExcludeModelIds().isEmpty()) {
            HashSet<String> deny = new HashSet<>(filters.getExcludeModelIds());
            result = result.stream().filter(id -> !deny.contains(id)).toList();
        }

        if (filters.getExcludeContains() != null && !filters.getExcludeContains().isEmpty()) {
            List<String> parts = filters.getExcludeContains();
            result = result.stream()
                    .filter(id -> parts.stream().noneMatch(id::contains))
                    .toList();
        }

        return result;
    }

    private ModelInfo extractModelInfo(JsonNode modelNode) {
        Set<ModelCapabilities> capabilities = OpenRouterModelCapabilitiesMapper.fromOpenRouterModel(modelNode);
        return new ModelInfo(capabilities);
    }

    private static final class ModelStats {
        volatile int lastStatus;
        volatile double ewmaLatencyMs;
        volatile long cooldownUntilEpochMs;
        volatile long lastUpdatedAtEpochMs;
    }

    private record ModelInfo(Set<ModelCapabilities> capabilities) {
        private static final ModelInfo UNKNOWN = new ModelInfo(Set.of());
    }

    private void logRankingDecision(List<String> selectedCandidates, long nowEpochMs) {
        if (selectedCandidates == null || selectedCandidates.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String modelId : selectedCandidates) {
            ModelStats stats = statsByModelId.get(modelId);
            ModelInfo info = infoByModelId.getOrDefault(modelId, ModelInfo.UNKNOWN);
            double score = score(modelId, nowEpochMs);
            sb.append("\n- model=").append(modelId)
                    .append(" score=").append(formatDouble(score));
            sb.append(" capabilities=").append(info.capabilities().stream().map(Enum::name).sorted().toList());
            if (stats != null) {
                sb.append(" ewmaMs=").append(formatDouble(stats.ewmaLatencyMs))
                        .append(" lastStatus=").append(stats.lastStatus)
                        .append(" cooldownUntil=").append(stats.cooldownUntilEpochMs == 0 ? "-" : stats.cooldownUntilEpochMs);
            } else {
                sb.append(" ewmaMs=- lastStatus=- cooldownUntil=-");
            }
        }
        log.info("OpenRouter free model ranking: selected candidates (nowEpochMs={}){}", nowEpochMs, sb);
    }

    private String formatDouble(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
