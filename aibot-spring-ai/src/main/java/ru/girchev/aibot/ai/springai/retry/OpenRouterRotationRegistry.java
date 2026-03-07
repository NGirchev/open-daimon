package ru.girchev.aibot.ai.springai.retry;

import ru.girchev.aibot.ai.springai.config.SpringAIModelConfig;
import ru.girchev.aibot.common.ai.ModelCapabilities;

import java.util.List;
import java.util.Set;

/**
 * Контракт для аспекта ротации моделей OpenRouter: получение кандидатов и запись статистики.
 * Позволяет в тестах мокать реестр без загрузки тяжёлых зависимостей.
 */
public interface OpenRouterRotationRegistry extends OpenRouterModelStatsRecorder {

    /**
     * Кандидаты по capabilities, с опциональным предпочитаемым именем (первым в списке).
     */
    List<SpringAIModelConfig> getCandidatesByCapabilities(Set<ModelCapabilities> required, String preferredModelId);
}
