package io.github.ngirchev.opendaimon.ai.springai.retry;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;

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
        String baseUrl = normalizeOpenRouterBaseUrl(openRouterProperties.getApi().getUrl().trim());
        List<OpenRouterModelEntry> fetched = openRouterClient.fetchModels(baseUrl, openRouterProperties.getApi().getKey());
        if (fetched.isEmpty()) {
            return;
        }
        Set<String> openRouterIds = fetched.stream().map(OpenRouterModelEntry::id).filter(StringUtils::hasText).collect(Collectors.toSet());

        // Remove yml OPENAI models not present in OpenRouter response
        List<String> removedYmlModels = new ArrayList<>();
        for (String name : new ArrayList<>(modelsByName.keySet())) {
            if (!ymlModelNames.contains(name)) {
                continue;
            }
            SpringAIModelConfig config = modelsByName.get(name);
            if (config != null && config.getProviderType() == SpringAIModelConfig.ProviderType.OPENAI
                    && !openRouterIds.contains(name)) {
                modelsByName.remove(name);
                removedYmlModels.add(name);
            }
        }
        if (!removedYmlModels.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("yml models removed from registry (not found in OpenRouter API), count=").append(removedYmlModels.size()).append(":\n");
            for (String name : removedYmlModels) {
                sb.append("  - ").append(name).append("\n");
            }
            log.warn(sb.toString());
        }

        // Free models from API and after filters
        List<String> freeFromApi = fetched.stream().filter(OpenRouterModelEntry::free).map(OpenRouterModelEntry::id).toList();
        List<String> freeFiltered = applyPropertyFilters(freeFromApi);
        Set<String> keysBeforeAdd = new HashSet<>(modelsByName.keySet());

        // Add free models from response that are not yet in registry
        long now = System.currentTimeMillis();
        for (OpenRouterModelEntry entry : fetched) {
            if (!entry.free() || !freeFiltered.contains(entry.id())) {
                continue;
            }
            if (modelsByName.containsKey(entry.id())) {
                continue;
            }
            ModelStats existingStats = statsByModelId.get(entry.id());
            if (existingStats != null && existingStats.cooldownUntilEpochMs > now) {
                log.debug("OpenRouter free model skipped (cooldown active): model={}, cooldownRemainingMs={}",
                        entry.id(), existingStats.cooldownUntilEpochMs - now);
                continue;
            }
            Set<ModelCapabilities> caps = OpenRouterModelCapabilitiesMapper.fromOpenRouterModel(entry.node(), true);
            SpringAIModelConfig config = getSpringAIModelConfig(entry, caps);
            modelsByName.put(entry.id(), config);
            log.debug("Added OpenRouter free model to registry: {}", entry.id());
        }

        logExcludedAndAlreadyPresent(freeFromApi, freeFiltered, keysBeforeAdd);

        // Paid (non-free) models matched by whitelist — only when whitelist is configured
        List<OpenRouterModelsProperties.Whitelist> whitelists = openRouterProperties.getWhitelist();
        if (whitelists != null && !whitelists.isEmpty()) {
            for (OpenRouterModelEntry entry : fetched) {
                if (entry.free()) continue;                          // already handled above
                if (modelsByName.containsKey(entry.id())) continue; // already in registry (e.g. from yml)
                if (isBlacklisted(entry.id())) continue;
                if (whitelists.stream().noneMatch(wl -> matchesWhitelist(entry.id(), wl))) continue;
                ModelStats existingStats = statsByModelId.get(entry.id());
                if (existingStats != null && existingStats.cooldownUntilEpochMs > now) continue;
                Set<ModelCapabilities> caps = OpenRouterModelCapabilitiesMapper.fromOpenRouterModel(entry.node(), false);
                SpringAIModelConfig config = getSpringAIModelConfig(entry, caps);
                modelsByName.put(entry.id(), config);
                log.debug("Added OpenRouter paid (whitelisted) model to registry: {}", entry.id());
            }
        }

        logRegistrySnapshot("after OpenRouter sync");
    }

    private SpringAIModelConfig getSpringAIModelConfig(OpenRouterModelEntry entry, Set<ModelCapabilities> caps) {
        SpringAIModelConfig config = new SpringAIModelConfig();
        config.setName(entry.id());
        config.setCapabilities(caps);
        config.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
        config.setPriority(OPENROUTER_FREE_PRIORITY);
        List<UserPriority> allowedRoles = computeAllowedRoles(entry.id());
        if (allowedRoles != null) {
            config.setAllowedRoles(allowedRoles);
        }
        return config;
    }

    /**
     * Determines which roles are allowed to use the given model based on whitelist entries.
     * Returns null if all roles are allowed (no whitelist, or a matching entry has no role restriction).
     */
    private List<UserPriority> computeAllowedRoles(String modelId) {
        List<OpenRouterModelsProperties.Whitelist> whitelists = openRouterProperties.getWhitelist();
        if (whitelists == null || whitelists.isEmpty()) {
            return null;
        }
        List<UserPriority> collected = new ArrayList<>();
        for (OpenRouterModelsProperties.Whitelist wl : whitelists) {
            if (!matchesWhitelist(modelId, wl)) {
                continue;
            }
            if (wl.getRoles() == null || wl.getRoles().isEmpty()) {
                return null;
            }
            collected.addAll(wl.getRoles());
        }
        if (collected.isEmpty()) {
            return null;
        }
        return collected.stream().distinct().toList();
    }

    /**
     * Returns true if modelId is matched by the given whitelist entry.
     * A whitelist with no include rules matches everything.
     */
    private static boolean matchesWhitelist(String modelId, OpenRouterModelsProperties.Whitelist wl) {
        boolean hasIncludeIds = wl.getIncludeModelIds() != null && !wl.getIncludeModelIds().isEmpty();
        boolean hasIncludeContains = wl.getIncludeContains() != null && !wl.getIncludeContains().isEmpty();
        if (!hasIncludeIds && !hasIncludeContains) {
            return true;
        }
        if (hasIncludeIds && wl.getIncludeModelIds().contains(modelId)) {
            return true;
        }
        if (hasIncludeContains && wl.getIncludeContains().stream().anyMatch(modelId::contains)) {
            return true;
        }
        return false;
    }

    private boolean isBlacklisted(String modelId) {
        OpenRouterModelsProperties.Blacklist blacklist = openRouterProperties.getBlacklist();
        if (blacklist == null) return false;
        if (blacklist.getExcludeModelIds() != null && blacklist.getExcludeModelIds().contains(modelId)) return true;
        if (blacklist.getExcludeContains() != null && blacklist.getExcludeContains().stream().anyMatch(modelId::contains)) return true;
        return false;
    }

    private static String normalizeOpenRouterBaseUrl(String url) {
        if (url.endsWith("/v1/chat/completions")) {
            return url.substring(0, url.length() - "/v1/chat/completions".length());
        }
        if (url.endsWith("/v1")) {
            return url.substring(0, url.length() - "/v1".length());
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
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
        OpenRouterModelsProperties.Blacklist blacklist = openRouterProperties.getBlacklist();
        if (blacklist != null) {
            if (blacklist.getExcludeModelIds() != null && blacklist.getExcludeModelIds().contains(modelId)) {
                return "in blacklist.exclude-model-ids";
            }
            if (blacklist.getExcludeContains() != null && !blacklist.getExcludeContains().isEmpty()) {
                if (blacklist.getExcludeContains().stream().anyMatch(modelId::contains)) {
                    return "matches blacklist.exclude-contains";
                }
            }
        }
        List<OpenRouterModelsProperties.Whitelist> whitelists = openRouterProperties.getWhitelist();
        if (whitelists != null && !whitelists.isEmpty()) {
            boolean matchesAny = whitelists.stream().anyMatch(wl -> matchesWhitelist(modelId, wl));
            if (!matchesAny) {
                return "not matched by any whitelist entry";
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
            String roles = (c.getAllowedRoles() == null || c.getAllowedRoles().isEmpty())
                    ? "ALL"
                    : c.getAllowedRoles().stream().map(Enum::name).toList().toString();
            sb.append("  ").append(c.getName())
                    .append(" | capabilities=").append(caps)
                    .append(" | priority=").append(c.getPriority())
                    .append(" | provider=").append(c.getProviderType())
                    .append(" | roles=").append(roles)
                    .append("\n");
        }
        log.info(sb.toString());
    }

    private List<String> applyPropertyFilters(List<String> modelIds) {
        if (modelIds == null || modelIds.isEmpty()) {
            return modelIds;
        }
        List<String> result = new ArrayList<>(modelIds);

        // 1. Apply blacklist first
        result = result.stream().filter(id -> !isBlacklisted(id)).toList();

        // 2. Apply whitelist: keep only models matched by at least one whitelist entry
        List<OpenRouterModelsProperties.Whitelist> whitelists = openRouterProperties.getWhitelist();
        if (whitelists != null && !whitelists.isEmpty()) {
            result = result.stream()
                    .filter(id -> whitelists.stream().anyMatch(wl -> matchesWhitelist(id, wl)))
                    .toList();
        }

        return result;
    }

    /**
     * Candidates by capabilities, with optional preferred name (first in list).
     */
    public List<SpringAIModelConfig> getCandidatesByCapabilities(Set<ModelCapabilities> required, String preferredModelId) {
        return getCandidatesByCapabilities(required, preferredModelId, null);
    }

    /**
     * Candidates by capabilities and user role, with optional preferred name (first in list).
     * If userPriority is null — role filtering is skipped.
     */
    public List<SpringAIModelConfig> getCandidatesByCapabilities(Set<ModelCapabilities> required, String preferredModelId, UserPriority userPriority) {
        if (required == null || required.isEmpty()) {
            return List.of();
        }
        List<SpringAIModelConfig> candidates = new ArrayList<>();
        for (SpringAIModelConfig model : modelsByName.values()) {
            Set<ModelCapabilities> caps = model.getCapabilities();
            if (caps == null || caps.isEmpty()) {
                continue;
            }
            Integer maxIndex = findMaxIndexForAllTypes(new ArrayList<>(caps), required);
            if (maxIndex == null) {
                continue;
            }
            if (userPriority != null && !model.isAllowedForRole(userPriority)) {
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
                Comparator.<SpringAIModelConfig>comparingInt(m -> findMaxIndexForAllTypes(new ArrayList<>(m.getCapabilities()), required))
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
                candidates.addFirst(preferred.get());
            }
        }
        return List.copyOf(candidates);
    }

    /**
     * All models visible to the given user role, sorted by priority then name.
     * If userPriority is null — role filtering is skipped.
     */
    public List<SpringAIModelConfig> getAllModels(UserPriority userPriority) {
        return modelsByName.values().stream()
                .filter(m -> userPriority == null || m.isAllowedForRole(userPriority))
                .sorted(Comparator.comparing(SpringAIModelConfig::getPriority)
                        .thenComparing(SpringAIModelConfig::getName))
                .toList();
    }

    public Optional<SpringAIModelConfig> getByModelName(String name) {
        if (!StringUtils.hasText(name)) {
            return Optional.empty();
        }
        return Optional.ofNullable(modelsByName.get(name));
    }

    public Set<ModelCapabilities> getCapabilities(String modelId) {
        SpringAIModelConfig config = modelsByName.get(modelId);
        if (config == null || config.getCapabilities() == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(config.getCapabilities());
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
            } else if (status == 404 && ranking.getCooldown404() != null) {
                cooldownMs = ranking.getCooldown404().toMillis();
            } else if (status >= 500 && status <= 599 && ranking.getCooldown5xx() != null) {
                cooldownMs = ranking.getCooldown5xx().toMillis();
            }
            if (cooldownMs > 0) {
                stats.cooldownUntilEpochMs = System.currentTimeMillis() + cooldownMs;
                log.info("OpenRouter model cooldown: model={}, status={}, cooldownMs={}", modelId, status, cooldownMs);
            }
        }
        if (status == 404) {
            if (!ymlModelNames.contains(modelId)) {
                modelsByName.remove(modelId);
                log.warn("OpenRouter model removed from registry on 404: model={}", modelId);
            }
            logModelDetailsFromOpenRouter(modelId);
        }
    }

    private void logModelDetailsFromOpenRouter(String modelId) {
        if (openRouterClient == null || openRouterProperties == null || openRouterProperties.getApi() == null) {
            return;
        }
        try {
            String baseUrl = normalizeOpenRouterBaseUrl(openRouterProperties.getApi().getUrl().trim());
            String apiKey = openRouterProperties.getApi().getKey();
            JsonNode node = openRouterClient.fetchModelDetails(baseUrl, apiKey, modelId);
            if (node == null || node.isMissingNode() || node.isNull()) {
                log.warn("OpenRouter 404 diagnostic: model={} not found via GET /v1/models/{}", modelId, modelId);
            } else {
                log.warn("OpenRouter 404 diagnostic: model={} exists in API. architecture={}, supported_parameters={}",
                        modelId, node.path("architecture"), node.path("supported_parameters"));
            }
        } catch (Exception e) {
            log.warn("OpenRouter 404 diagnostic fetch failed for model={}: {}", modelId, e.getMessage());
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
