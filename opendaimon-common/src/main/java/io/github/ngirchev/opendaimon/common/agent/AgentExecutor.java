package io.github.ngirchev.opendaimon.common.agent;

import reactor.core.publisher.Flux;

/**
 * Public API for executing agent tasks.
 *
 * <p>An {@code AgentExecutor} receives a task description via {@link AgentRequest},
 * runs an autonomous agent loop (e.g., ReAct), and returns the result.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@code ReActAgentExecutor} — FSM-based ReAct loop (opendaimon-spring-ai)</li>
 * </ul>
 */
public interface AgentExecutor {

    /**
     * Executes an agent task synchronously.
     *
     * @param request task description, constraints, and configuration
     * @return execution result with final answer and step history
     */
    AgentResult execute(AgentRequest request);

    /**
     * Executes an agent task and streams intermediate events.
     *
     * <p>Events are emitted as the agent progresses: thinking, tool calls,
     * observations, and the final answer. The Flux completes when the agent
     * reaches a terminal state.
     *
     * <p>Default implementation runs synchronously and emits a single
     * FINAL_ANSWER event. Override for true streaming.
     *
     * @param request task description, constraints, and configuration
     * @return stream of agent events
     */
    default Flux<AgentStreamEvent> executeStream(AgentRequest request) {
        return Flux.defer(() -> {
            AgentResult result = execute(request);
            if (result.isSuccess()) {
                return Flux.just(AgentStreamEvent.finalAnswer(
                        result.finalAnswer(), result.iterationsUsed()));
            } else {
                return Flux.just(AgentStreamEvent.error(
                        "Agent finished in state: " + result.terminalState(),
                        result.iterationsUsed()));
            }
        });
    }
}
