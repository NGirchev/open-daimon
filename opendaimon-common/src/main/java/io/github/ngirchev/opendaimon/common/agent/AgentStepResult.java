package io.github.ngirchev.opendaimon.common.agent;

import java.time.Instant;

/**
 * Captures one complete ReAct iteration: thought, action, and observation.
 *
 * @param iteration   zero-based iteration index
 * @param thought     LLM reasoning about what to do next
 * @param action      tool name invoked (null if final answer iteration)
 * @param actionInput tool arguments as JSON string (null if final answer)
 * @param observation tool execution result (null if final answer)
 * @param timestamp   when this iteration completed
 */
public record AgentStepResult(
        int iteration,
        String thought,
        String action,
        String actionInput,
        String observation,
        Instant timestamp
) {
}
