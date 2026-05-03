package io.github.ngirchev.opendaimon.common.agent.orchestration;

import java.util.List;
import java.util.Map;

/**
 * A single step in an orchestration plan.
 *
 * @param id          unique step identifier within the plan
 * @param name        human-readable step name (e.g., "Research topic")
 * @param task        natural language task description for the agent
 * @param dependsOn   IDs of steps that must complete before this one starts
 * @param params      additional parameters passed to the agent (e.g., model override)
 * @param maxIterations override for agent max iterations (null = use default)
 */
public record OrchestrationStep(
        String id,
        String name,
        String task,
        List<String> dependsOn,
        Map<String, String> params,
        Integer maxIterations
) {

    public OrchestrationStep(String id, String name, String task) {
        this(id, name, task, List.of(), Map.of(), null);
    }

    public OrchestrationStep(String id, String name, String task, List<String> dependsOn) {
        this(id, name, task, dependsOn, Map.of(), null);
    }

    public boolean hasDependencies() {
        return dependsOn != null && !dependsOn.isEmpty();
    }
}
