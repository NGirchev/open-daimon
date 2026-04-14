package io.github.ngirchev.opendaimon.ai.springai.agent;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the agent framework.
 *
 * <p>Properties namespace: {@code open-daimon.agent.*}
 *
 * <p>Example:
 * <pre>
 * open-daimon:
 *   agent:
 *     enabled: true
 *     max-iterations: 10
 *     default-model: openai/gpt-4o-mini
 * </pre>
 */
@ConfigurationProperties(prefix = "open-daimon.agent")
@Getter
@Setter
public class AgentProperties {

    /** Feature flag: when true, agent auto-configuration and beans are enabled. */
    private boolean enabled;

    /** Maximum number of ReAct loop iterations before forced termination. */
    private int maxIterations;

    /** Default model name for agent LLM calls (null = use default from Spring AI config). */
    private String defaultModel;

    /** Similarity threshold for semantic memory recall (0.0 to 1.0). */
    private double memorySimilarityThreshold;
}
