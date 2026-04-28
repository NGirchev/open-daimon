package io.github.ngirchev.opendaimon.common.agent.orchestration;

/**
 * Executes multi-step orchestration plans.
 *
 * <p>An orchestrator takes a {@link OrchestrationPlan} (a DAG of steps),
 * resolves dependencies, executes each step via {@link io.github.ngirchev.opendaimon.common.agent.AgentExecutor},
 * and handles error recovery.
 *
 * <p>Each step receives the outputs of its dependencies as context,
 * enabling data flow between steps (e.g., "research" output feeds into "summarize").
 */
public interface AgentOrchestrator {

    /**
     * Executes an orchestration plan synchronously.
     *
     * @param plan the plan to execute
     * @return execution result with step-level details
     */
    OrchestrationResult execute(OrchestrationPlan plan);
}
