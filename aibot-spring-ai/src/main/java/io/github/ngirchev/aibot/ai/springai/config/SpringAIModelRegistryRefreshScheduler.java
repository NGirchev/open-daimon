package io.github.ngirchev.aibot.ai.springai.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import io.github.ngirchev.aibot.ai.springai.retry.SpringAIModelRegistry;

/**
 * Periodically refreshes OpenRouter models list in the registry.
 */
@Slf4j
@RequiredArgsConstructor
public class SpringAIModelRegistryRefreshScheduler {

    private final SpringAIModelRegistry registry;

    @Scheduled(
            initialDelayString = "${ai-bot.ai.spring-ai.openrouter-auto-rotation.models.refresh-initial-delay:60s}",
            fixedDelayString = "${ai-bot.ai.spring-ai.openrouter-auto-rotation.models.refresh-interval:1h}"
    )
    public void refreshOpenRouterModels() {
        try {
            registry.refreshOpenRouterModels();
            log.debug("SpringAIModelRegistry OpenRouter models refresh completed");
        } catch (Exception e) {
            log.warn("Failed to refresh OpenRouter models in registry. reason={}", e.getMessage(), e);
        }
    }
}
