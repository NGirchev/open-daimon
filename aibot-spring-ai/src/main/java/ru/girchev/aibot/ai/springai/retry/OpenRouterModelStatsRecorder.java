package ru.girchev.aibot.ai.springai.retry;

/**
 * Запись успехов/неудач вызовов моделей для ранжирования (cooldown, ewma).
 * Реализуется реестром моделей; вызовы для моделей без capability FREE игнорируются внутри реализации.
 */
public interface OpenRouterModelStatsRecorder {

    void recordSuccess(String modelId, long latencyMs);

    void recordFailure(String modelId, int status, long latencyMs);
}
