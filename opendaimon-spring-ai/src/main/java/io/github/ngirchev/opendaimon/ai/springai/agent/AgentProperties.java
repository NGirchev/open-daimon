package io.github.ngirchev.opendaimon.ai.springai.agent;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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
 *     stream-timeout-seconds: 600
 *     default-model: openai/gpt-4o-mini
 * </pre>
 */
@ConfigurationProperties(prefix = "open-daimon.agent")
@Validated
@Getter
@Setter
public class AgentProperties {

    /** Feature flag: when true, agent auto-configuration and beans are enabled. */
    private boolean enabled;

    /** Maximum number of ReAct loop iterations before forced termination. */
    private int maxIterations;

    /**
     * Upper bound on how long {@link SpringAgentLoopActions} will wait for a streaming
     * LLM response to complete, in seconds. Mapped to {@link java.time.Duration} at
     * bean wiring time.
     */
    @NotNull(message = "streamTimeoutSeconds is required")
    @Min(value = 1, message = "streamTimeoutSeconds must be >= 1")
    private Integer streamTimeoutSeconds;

    /** Default model name for agent LLM calls (null = use default from Spring AI config). */
    private String defaultModel;
}
