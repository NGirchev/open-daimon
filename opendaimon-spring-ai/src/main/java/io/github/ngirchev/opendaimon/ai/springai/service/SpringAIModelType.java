package io.github.ngirchev.opendaimon.ai.springai.service;

import lombok.RequiredArgsConstructor;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.service.AIUtils;

import java.util.*;

@RequiredArgsConstructor
public class SpringAIModelType {

    private final List<SpringAIModelConfig> models;

    public SpringAIModelConfig valueOfByRawName(String modelName) {
        return models.stream()
                .filter(model -> model.getName().equals(modelName))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(AIUtils.NO_SUPPORTED_AI_GATEWAY));
    }

    /**
     * Finds model by set of model types. Returns model that supports ALL requested types.
     * Selection: 1) For each model, max index among requested types in its capabilities.
     * 2) Choose model with smallest max index. 3) Tie-break by priority (lower = higher priority; free=1, paid=2).
     *
     * @param modelCapabilities set of model types to search for
     * @return Optional with found model or empty if none matches
     */
    public Optional<SpringAIModelConfig> getByCapabilities(Set<ModelCapabilities> modelCapabilities) {
        if (modelCapabilities == null || modelCapabilities.isEmpty()) {
            return Optional.empty();
        }
        
        Map<SpringAIModelConfig, Integer> rangedModels = new HashMap<>();
        
        for (SpringAIModelConfig model : models) {
            List<ModelCapabilities> capabilities = model.getCapabilities();
            if (capabilities == null || capabilities.isEmpty()) {
                continue;
            }
            
            // For each model find max index among requested types in its capabilities
            Integer maxIndex = findMaxIndexForAllTypes(capabilities, modelCapabilities);
            if (maxIndex == null) {
                // Model does not support all requested types — skip
                continue;
            }
            
            // Store model with its max index for comparison
            rangedModels.put(model, maxIndex);
        }
        
        if (rangedModels.isEmpty()) {
            return Optional.empty();
        }
        
        // Find minimum maxIndex among all models
        Integer minMaxIndex = rangedModels.values().stream()
                .min(Integer::compareTo)
                .orElse(Integer.MAX_VALUE);
        
        // Filter by min maxIndex, then choose by min priority (lower number = higher priority)
        return rangedModels.entrySet().stream()
                .filter(entry -> entry.getValue().equals(minMaxIndex))
                .min(Comparator.comparing((Map.Entry<SpringAIModelConfig, Integer> entry) -> entry.getKey().getPriority())
                        .thenComparing(entry -> entry.getKey().getName()))
                .map(Map.Entry::getKey);
    }

    /**
     * Checks that capabilities list contains all requested types and returns max index among found types. Returns null if not all found.
     *
     * @param capabilities model capabilities list
     * @param requestedTypes requested model types
     * @return max index among found types or null if not all types found
     */
    private Integer findMaxIndexForAllTypes(List<ModelCapabilities> capabilities, Set<ModelCapabilities> requestedTypes) {
        Map<ModelCapabilities, Integer> typeIndices = new HashMap<>();
        
        // Find indices of requested types in capabilities
        for (int i = 0; i < capabilities.size(); i++) {
            ModelCapabilities capability = capabilities.get(i);
            if (requestedTypes.contains(capability)) {
                typeIndices.put(capability, i);
            }
        }
        
        // Ensure all requested types are present
        if (typeIndices.size() != requestedTypes.size()) {
            return null;
        }
        
        // Return max index
        return typeIndices.values().stream()
                .max(Integer::compareTo)
                .orElse(null);
    }

    /**
     * Finds model by single capability. Convenience for backward compatibility.
     *
     * @param modelCapabilities model type to search for
     * @return Optional with found model or empty if none matches
     */
    public Optional<SpringAIModelConfig> getByCapability(ModelCapabilities modelCapabilities) {
        if (modelCapabilities == null) {
            return Optional.empty();
        }
        return getByCapabilities(Set.of(modelCapabilities));
    }

    /**
     * Returns the first model from the configured list (application.yaml models.list order).
     * Use when a default model is needed (e.g. to choose OpenAI vs Ollama client).
     */
    public Optional<SpringAIModelConfig> getFirstModel() {
        return models.isEmpty() ? Optional.empty() : Optional.of(models.get(0));
    }

    public Optional<SpringAIModelConfig> getByModelName(String modelName) {
        return models.stream()
                .filter(model -> model.getName().equals(modelName))
                .findFirst();
    }

    public boolean isOpenAIModel(String modelName) {
        return getByModelName(modelName)
                .map(model -> model.getProviderType() == SpringAIModelConfig.ProviderType.OPENAI)
                .orElse(false);
    }

    public boolean isOllamaModel(String modelName) {
        return getByModelName(modelName)
                .map(model -> model.getProviderType() == SpringAIModelConfig.ProviderType.OLLAMA)
                .orElse(false);
    }
}



