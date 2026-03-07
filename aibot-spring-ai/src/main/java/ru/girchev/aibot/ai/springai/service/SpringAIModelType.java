package ru.girchev.aibot.ai.springai.service;

import lombok.RequiredArgsConstructor;
import ru.girchev.aibot.ai.springai.config.SpringAIModelConfig;
import ru.girchev.aibot.common.ai.ModelCapabilities;

import java.util.*;

@RequiredArgsConstructor
public class SpringAIModelType {

    private final List<SpringAIModelConfig> models;

    public SpringAIModelConfig valueOfByRawName(String modelName) {
        return models.stream()
                .filter(model -> model.getName().equals(modelName))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No supported AI gateway found"));
    }

    /**
     * Находит модель по набору типов моделей.
     * Возвращает модель, которая поддерживает ВСЕ запрошенные типы.
     * 
     * Алгоритм выбора:
     * 1. Для каждой модели находим максимальный индекс среди всех запрошенных типов в её capabilities
     * 2. Выбираем модель с минимальным значением этого максимального индекса
     * 3. Если несколько моделей имеют одинаковый maxIndex, выбираем модель с минимальным приоритетом
     *    (меньшее число = выше приоритет, бесплатные модели имеют приоритет 1, платные - 2)
     * 
     * Пример:
     * Запрошены типы: {CHAT, EMBEDDING}
     * - Модель A: [CHAT(0), EMBEDDING(1)], priority=1 → maxIndex = 1
     * - Модель B: [CHAT(0), EMBEDDING(1)], priority=2 → maxIndex = 1
     * - Модель C: [CHAT(0), TOOL_CALLING(1), EMBEDDING(2)], priority=1 → maxIndex = 2
     * Выбирается Модель A, так как у неё maxIndex (1) минимальный и приоритет (1) выше, чем у Модели B
     * 
     * @param modelCapabilities набор типов моделей для поиска
     * @return Optional с найденной моделью или пустой Optional, если подходящая модель не найдена
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
            
            // Для каждой модели находим максимальный индекс среди всех запрошенных типов
            // Например, если запрошены {CHAT, EMBEDDING} и модель имеет [CHAT(0), EMBEDDING(1)],
            // то maxIndex = 1 (максимальный индекс среди найденных типов)
            Integer maxIndex = findMaxIndexForAllTypes(capabilities, modelCapabilities);
            if (maxIndex == null) {
                // Модель не содержит все запрошенные типы - пропускаем
                continue;
            }
            
            // Сохраняем модель с её максимальным индексом для последующего сравнения
            rangedModels.put(model, maxIndex);
        }
        
        if (rangedModels.isEmpty()) {
            return Optional.empty();
        }
        
        // Находим минимальный maxIndex среди всех моделей
        Integer minMaxIndex = rangedModels.values().stream()
                .min(Integer::compareTo)
                .orElse(Integer.MAX_VALUE);
        
        // Фильтруем модели с минимальным maxIndex и выбираем модель с минимальным приоритетом
        // (меньшее число = выше приоритет)
        return rangedModels.entrySet().stream()
                .filter(entry -> entry.getValue().equals(minMaxIndex))
                .min(Comparator.comparing((Map.Entry<SpringAIModelConfig, Integer> entry) -> entry.getKey().getPriority())
                        .thenComparing(entry -> entry.getKey().getName()))
                .map(Map.Entry::getKey);
    }

    /**
     * Проверяет, содержит ли список capabilities все запрошенные типы,
     * и возвращает максимальный индекс среди всех найденных типов.
     * 
     * Пример:
     * capabilities = [CHAT, EMBEDDING, TOOL_CALLING]
     * requestedTypes = {CHAT, EMBEDDING}
     * Результат: 1 (максимальный индекс среди найденных: CHAT на 0, EMBEDDING на 1)
     * 
     * Если не все типы найдены, возвращает null.
     * 
     * @param capabilities список capabilities модели
     * @param requestedTypes запрошенные типы моделей
     * @return максимальный индекс среди всех найденных типов или null, если не все типы найдены
     */
    private Integer findMaxIndexForAllTypes(List<ModelCapabilities> capabilities, Set<ModelCapabilities> requestedTypes) {
        Map<ModelCapabilities, Integer> typeIndices = new HashMap<>();
        
        // Находим индексы всех запрошенных типов в capabilities
        for (int i = 0; i < capabilities.size(); i++) {
            ModelCapabilities capability = capabilities.get(i);
            if (requestedTypes.contains(capability)) {
                typeIndices.put(capability, i);
            }
        }
        
        // Проверяем, что все запрошенные типы найдены
        if (typeIndices.size() != requestedTypes.size()) {
            return null;
        }
        
        // Возвращаем максимальный индекс
        return typeIndices.values().stream()
                .max(Integer::compareTo)
                .orElse(null);
    }

    /**
     * Находит модель по одному типу модели.
     * Удобный метод для обратной совместимости.
     * 
     * @param modelCapabilities тип модели для поиска
     * @return Optional с найденной моделью или пустой Optional, если подходящая модель не найдена
     */
    public Optional<SpringAIModelConfig> getByCapability(ModelCapabilities modelCapabilities) {
        if (modelCapabilities == null) {
            return Optional.empty();
        }
        return getByCapabilities(Set.of(modelCapabilities));
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



