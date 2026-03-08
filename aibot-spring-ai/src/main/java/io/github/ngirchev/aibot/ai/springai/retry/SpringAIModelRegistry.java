package io.github.ngirchev.aibot.ai.springai.retry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import io.github.ngirchev.aibot.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.aibot.common.ai.ModelCapabilities;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory model registry: yml + OpenRouter free models. Model selection is from this list only (by capabilities and optionally by name). Thread-safe.
 */
@Slf4j
public class SpringAIModelRegistry implements OpenRouterRotationRegistry {

    private static final int OPENROUTER_FREE_PRIORITY = 100;

    private final List<SpringAIModelConfig> ymlModels;
    private final Set<String> ymlModelNames;
    private final OpenRouterModelsApiClient openRouterClient;
    private final OpenRouterModelsProperties openRouterProperties;

    /** Name -> config. Models from yml + OpenRouter additions. */
    private final Map<String, SpringAIModelConfig> modelsByName = new ConcurrentHashMap<>();
    /** Stats for ranking FREE models. */
    private final Map<String, ModelStats> statsByModelId = new ConcurrentHashMap<>();

    public SpringAIModelRegistry(
            List<SpringAIModelConfig> ymlModels,
            OpenRouterModelsApiClient openRouterClient,
            OpenRouterModelsProperties openRouterProperties) {
        this.ymlModels = ymlModels != null ? List.copyOf(ymlModels) : List.of();
        this.ymlModelNames = this.ymlModels.stream()
                .map(SpringAIModelConfig::getName)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        this.openRouterClient = openRouterClient;
        this.openRouterProperties = openRouterProperties != null ? openRouterProperties : new OpenRouterModelsProperties();
        initFromYml();
    }

    private void initFromYml() {
        for (SpringAIModelConfig c : ymlModels) {
            if (StringUtils.hasText(c.getName())) {
                modelsByName.put(c.getName(), c);
            }
        }
        log.info("SpringAIModelRegistry initialized with {} models from yml", modelsByName.size());
        logRegistrySnapshot("after yml init");
    }

    /**
     * Refreshes registry from OpenRouter: removes yml OPENAI models not in response; adds new free models from response.
     */
    public void refreshOpenRouterModels() {
        if (openRouterClient == null || openRouterProperties == null
                || openRouterProperties.getApi() == null
                || !StringUtils.hasText(openRouterProperties.getApi().getUrl())
                || !StringUtils.hasText(openRouterProperties.getApi().getKey())) {
            return;
        }
        String baseUrl = openRouterProperties.getApi().getUrl().trim();
        if (baseUrl.endsWith("/v1/chat/completions")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - "/v1/chat/completions".length());
        } else if (baseUrl.endsWith("/v1")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - "/v1".length());
        }
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        List<OpenRouterModelEntry> fetched = openRouterClient.fetchModels(baseUrl, openRouterProperties.getApi().getKey());
        if (fetched.isEmpty()) {
            return;
        }
        Set<String> openRouterIds = fetched.stream().map(OpenRouterModelEntry::id).filter(StringUtils::hasText).collect(Collectors.toSet());

        // Remove yml OPENAI models not present in OpenRouter response
        for (String name : new ArrayList<>(modelsByName.keySet())) {
            if (!ymlModelNames.contains(name)) {
                continue;
            }
            SpringAIModelConfig config = modelsByName.get(name);
            if (config != null && config.getProviderType() == SpringAIModelConfig.ProviderType.OPENAI
                    && !openRouterIds.contains(name)) {
                modelsByName.remove(name);
                log.info("Removed from registry (not in OpenRouter): {}", name);
            }
        }

        // Free models from API and after filters
        List<String> freeFromApi = fetched.stream().filter(OpenRouterModelEntry::free).map(OpenRouterModelEntry::id).toList();
        List<String> freeFiltered = applyPropertyFilters(freeFromApi);
        Set<String> keysBeforeAdd = new HashSet<>(modelsByName.keySet());

        // Add free models from response that are not yet in registry
        for (OpenRouterModelEntry entry : fetched) {
            if (!entry.free() || !freeFiltered.contains(entry.id())) {
                continue;
            }
            if (modelsByName.containsKey(entry.id())) {
                continue;
            }
            Set<ModelCapabilities> caps = OpenRouterModelCapabilitiesMapper.fromOpenRouterModel(entry.node(), true);
            SpringAIModelConfig config = new SpringAIModelConfig();
            config.setName(entry.id());
            config.setCapabilities(new ArrayList<>(caps));
            config.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
            config.setPriority(OPENROUTER_FREE_PRIORITY);
            modelsByName.put(entry.id(), config);
            log.debug("Added OpenRouter free model to registry: {}", entry.id());
        }

        logExcludedAndAlreadyPresent(freeFromApi, freeFiltered, keysBeforeAdd);
        logRegistrySnapshot("after OpenRouter sync");
    }

    /**
     * Logs: 1) free models from API excluded from final registry (by filters) and reason;
     * 2) free models already in registry (yml or previous sync) and not re-added.
     */
    private void logExcludedAndAlreadyPresent(List<String> freeFromApi, List<String> freeFiltered, Set<String> keysBeforeAdd) {
        if (!log.isInfoEnabled()) {
            return;
        }
        Set<String> filteredSet = new HashSet<>(freeFiltered);
        Set<String> fromApiSet = new HashSet<>(freeFromApi);

        // Excluded by filters (were in API free but filtered out)
        List<String> excludedByFilters = fromApiSet.stream().filter(id -> !filteredSet.contains(id)).sorted().toList();
        if (!excludedByFilters.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("OpenRouter free models NOT in final registry (excluded by filters), count=").append(excludedByFilters.size()).append(":\n");
            for (String id : excludedByFilters) {
                String reason = explainExcludedByFilter(id);
                sb.append("  - ").append(id).append(" | reason: ").append(reason).append("\n");
            }
            log.info(sb.toString());
        }

        // In freeFiltered but already in registry (not added again)
        List<String> alreadyPresent = freeFiltered.stream().filter(keysBeforeAdd::contains).sorted().toList();
        if (!alreadyPresent.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("OpenRouter free models already in registry (from yml or previous sync), not added again, count=").append(alreadyPresent.size()).append(":\n");
            for (String id : alreadyPresent) {
                sb.append("  - ").append(id).append("\n");
            }
            log.info(sb.toString());
        }
    }

    /**
     * Explains why model was excluded by filters (which filter excluded it).
     */
    private String explainExcludedByFilter(String modelId) {
        OpenRouterModelsProperties.Filters f = openRouterProperties.getFilters();
        if (f == null) {
            return "no filters configured";
        }
        if (f.getIncludeModelIds() != null && !f.getIncludeModelIds().isEmpty()) {
            if (!f.getIncludeModelIds().contains(modelId)) {
                return "not in include-model-ids (allowlist)";
            }
        }
        if (f.getIncludeContains() != null && !f.getIncludeContains().isEmpty()) {
            if (f.getIncludeContains().stream().noneMatch(modelId::contains)) {
                return "does not match include-contains (allowlist by substring)";
            }
        }
        if (f.getExcludeModelIds() != null && f.getExcludeModelIds().contains(modelId)) {
            return "in exclude-model-ids (denylist)";
        }
        if (f.getExcludeContains() != null && !f.getExcludeContains().isEmpty()) {
            if (f.getExcludeContains().stream().anyMatch(modelId::contains)) {
                return "matches exclude-contains (denylist by substring)";
            }
        }
        return "filter step removed it (check filter order)";
    }

    /**
     * Logs full registry: each model on new line with name and capabilities.
     */
    private void logRegistrySnapshot(String stage) {
        if (!log.isInfoEnabled()) {
            return;
        }
        List<SpringAIModelConfig> sorted = modelsByName.values().stream()
                .sorted(Comparator.comparing(SpringAIModelConfig::getPriority).thenComparing(SpringAIModelConfig::getName))
                .toList();
        StringBuilder sb = new StringBuilder();
        sb.append("SpringAIModelRegistry snapshot (").append(stage).append("), total=").append(sorted.size()).append(":\n");
        for (SpringAIModelConfig c : sorted) {
            String caps = c.getCapabilities() != null
                    ? c.getCapabilities().stream().map(Enum::name).sorted().toList().toString()
                    : "[]";
            sb.append("  ").append(c.getName())
                    .append(" | capabilities=").append(caps)
                    .append(" | priority=").append(c.getPriority())
                    .append(" | provider=").append(c.getProviderType())
                    .append("\n");
        }
        log.info(sb.toString());
    }

    private List<String> applyPropertyFilters(List<String> modelIds) {
        if (modelIds == null || modelIds.isEmpty()) {
            return modelIds;
        }
        OpenRouterModelsProperties.Filters filters = openRouterProperties.getFilters();
        if (filters == null) {
            return modelIds;
        }
        List<String> result = new ArrayList<>(modelIds);
        if (filters.getIncludeModelIds() != null && !filters.getIncludeModelIds().isEmpty()) {
            Set<String> allow = new HashSet<>(filters.getIncludeModelIds());
            result = result.stream().filter(allow::contains).toList();
        }
        if (filters.getIncludeContains() != null && !filters.getIncludeContains().isEmpty()) {
            result = result.stream()
                    .filter(id -> filters.getIncludeContains().stream().anyMatch(id::contains))
                    .toList();
        }
        if (filters.getExcludeModelIds() != null && !filters.getExcludeModelIds().isEmpty()) {
            Set<String> deny = new HashSet<>(filters.getExcludeModelIds());
            result = result.stream().filter(id -> !deny.contains(id)).toList();
        }
        if (filters.getExcludeContains() != null && !filters.getExcludeContains().isEmpty()) {
            result = result.stream()
                    .filter(id -> filters.getExcludeContains().stream().noneMatch(id::contains))
                    .toList();
        }
        return result;
    }

    /**
     * Candidates by capabilities, with optional preferred name (first in list).
     */
    public List<SpringAIModelConfig> getCandidatesByCapabilities(Set<ModelCapabilities> required, String preferredModelId) {
        if (required == null || required.isEmpty()) {
            return List.of();
        }
        List<SpringAIModelConfig> candidates = new ArrayList<>();
        for (SpringAIModelConfig model : modelsByName.values()) {
            List<ModelCapabilities> caps = model.getCapabilities();
            if (caps == null || caps.isEmpty()) {
                continue;
            }
            Integer maxIndex = findMaxIndexForAllTypes(caps, required);
            if (maxIndex == null) {
                continue;
            }
            candidates.add(model);
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        long now = System.currentTimeMillis();
        Comparator<SpringAIModelConfig> byPriority = Comparator
                .comparing(SpringAIModelConfig::getPriority)
                .thenComparing(SpringAIModelConfig::getName);
        candidates.sort(
                Comparator.<SpringAIModelConfig>comparingInt(m -> findMaxIndexForAllTypes(m.getCapabilities(), required))
                        .thenComparing(byPriority)
                        .thenComparing((SpringAIModelConfig m) -> -score(m.getName(), now))
                        .thenComparing(SpringAIModelConfig::getName)
        );

        if (StringUtils.hasText(preferredModelId)) {
            Optional<SpringAIModelConfig> preferred = candidates.stream()
                    .filter(m -> preferredModelId.equals(m.getName()))
                    .findFirst();
            if (preferred.isPresent()) {
                candidates.remove(preferred.get());
                candidates.add(0, preferred.get());
            }
        }
        return List.copyOf(candidates);
    }

    public Optional<SpringAIModelConfig> getByModelName(String name) {
        if (!StringUtils.hasText(name)) {
            return Optional.empty();
        }
        return Optional.ofNullable(modelsByName.get(name));
    }

    private static Integer findMaxIndexForAllTypes(List<ModelCapabilities> capabilities, Set<ModelCapabilities> requestedTypes) {
        Map<ModelCapabilities, Integer> typeIndices = new HashMap<>();
        for (int i = 0; i < capabilities.size(); i++) {
            ModelCapabilities c = capabilities.get(i);
            if (requestedTypes.contains(c)) {
                typeIndices.put(c, i);
            }
        }
        if (typeIndices.size() != requestedTypes.size()) {
            return null;
        }
        return typeIndices.values().stream().max(Integer::compareTo).orElse(null);
    }

    private double score(String modelId, long nowEpochMs) {
        SpringAIModelConfig config = modelsByName.get(modelId);
        if (config == null || !config.isFree()) {
            return 50.0d;
        }
        ModelStats stats = statsByModelId.get(modelId);
        if (stats == null) {
            return 50.0d;
        }
        if (stats.cooldownUntilEpochMs > nowEpochMs) {
            return -10_000.0d;
        }
        double base = 100.0d;
        base -= (stats.ewmaLatencyMs / 200.0d);
        if (stats.lastStatus == 429) {
            base -= 30.0d;
        } else if (stats.lastStatus >= 400 && stats.lastStatus <= 499) {
            base -= 120.0d;
        } else if (stats.lastStatus >= 500 && stats.lastStatus <= 599) {
            base -= 50.0d;
        }
        return base;
    }

    @Override
    public void recordSuccess(String modelId, long latencyMs) {
        if (!StringUtils.hasText(modelId)) {
            return;
        }
        SpringAIModelConfig config = modelsByName.get(modelId);
        if (config == null || !config.isFree()) {
            return;
        }
        ModelStats stats = statsByModelId.computeIfAbsent(modelId, k -> new ModelStats());
        double alpha = 0.2d;
        if (openRouterProperties.getRanking() != null && openRouterProperties.getRanking().getLatencyEwmaAlpha() != null) {
            alpha = openRouterProperties.getRanking().getLatencyEwmaAlpha();
        }
        stats.lastStatus = 200;
        stats.ewmaLatencyMs = ewma(stats.ewmaLatencyMs, latencyMs, alpha);
        stats.cooldownUntilEpochMs = 0;
        stats.lastUpdatedAtEpochMs = System.currentTimeMillis();
    }

    @Override
    public void recordFailure(String modelId, int status, long latencyMs) {
        if (!StringUtils.hasText(modelId)) {
            return;
        }
        SpringAIModelConfig config = modelsByName.get(modelId);
        if (config == null || !config.isFree()) {
            return;
        }
        ModelStats stats = statsByModelId.computeIfAbsent(modelId, k -> new ModelStats());
        double alpha = 0.2d;
        if (openRouterProperties.getRanking() != null && openRouterProperties.getRanking().getLatencyEwmaAlpha() != null) {
            alpha = openRouterProperties.getRanking().getLatencyEwmaAlpha();
        }
        stats.lastStatus = status;
        stats.ewmaLatencyMs = ewma(stats.ewmaLatencyMs, latencyMs, alpha);
        stats.lastUpdatedAtEpochMs = System.currentTimeMillis();
        OpenRouterModelsProperties.Ranking ranking = openRouterProperties.getRanking();
        if (ranking != null && Boolean.TRUE.equals(ranking.getEnabled())) {
            long cooldownMs = 0;
            if (status == 429 && ranking.getCooldown429() != null) {
                cooldownMs = ranking.getCooldown429().toMillis();
            } else if (status >= 500 && status <= 599 && ranking.getCooldown5xx() != null) {
                cooldownMs = ranking.getCooldown5xx().toMillis();
            }
            if (cooldownMs > 0) {
                stats.cooldownUntilEpochMs = System.currentTimeMillis() + cooldownMs;
            }
        }
    }

    private static double ewma(double current, long sampleMs, double alpha) {
        if (current <= 0) {
            return sampleMs;
        }
        return current * (1.0d - alpha) + sampleMs * alpha;
    }

    private static final class ModelStats {
        volatile int lastStatus;
        volatile double ewmaLatencyMs;
        volatile long cooldownUntilEpochMs;
        volatile long lastUpdatedAtEpochMs;
    }
}
