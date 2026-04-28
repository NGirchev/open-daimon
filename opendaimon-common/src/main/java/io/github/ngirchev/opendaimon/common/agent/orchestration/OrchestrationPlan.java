package io.github.ngirchev.opendaimon.common.agent.orchestration;

import java.util.List;
import java.util.Map;

/**
 * A multi-step orchestration plan consisting of a DAG of steps.
 *
 * <p>Steps are executed in dependency order: steps with no dependencies
 * run first, then steps whose dependencies are all completed.
 * Steps at the same level can potentially run in parallel (future enhancement).
 *
 * @param name            plan name for logging and tracking
 * @param conversationId  conversation scope for the entire plan
 * @param steps           ordered list of steps (topologically sorted by caller)
 * @param metadata        additional context for the plan
 */
public record OrchestrationPlan(
        String name,
        String conversationId,
        List<OrchestrationStep> steps,
        Map<String, String> metadata
) {

    public OrchestrationPlan(String name, String conversationId, List<OrchestrationStep> steps) {
        this(name, conversationId, steps, Map.of());
    }
}
