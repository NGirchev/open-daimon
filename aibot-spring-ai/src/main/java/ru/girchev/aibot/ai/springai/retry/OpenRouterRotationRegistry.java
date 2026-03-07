package ru.girchev.aibot.ai.springai.retry;

import ru.girchev.aibot.ai.springai.config.SpringAIModelConfig;
import ru.girchev.aibot.common.ai.ModelCapabilities;

import java.util.List;
import java.util.Set;

/**
 * Contract for OpenRouter model rotation aspect: get candidates and record stats. Allows tests to mock registry without heavy deps.
 */
public interface OpenRouterRotationRegistry extends OpenRouterModelStatsRecorder {

    /**
     * Candidates by capabilities, with optional preferred name (first in list).
     */
    List<SpringAIModelConfig> getCandidatesByCapabilities(Set<ModelCapabilities> required, String preferredModelId);
}
