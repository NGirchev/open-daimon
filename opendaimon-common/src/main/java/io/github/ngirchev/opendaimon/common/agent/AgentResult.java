package io.github.ngirchev.opendaimon.common.agent;

import java.time.Duration;
import java.util.List;

/**
 * Immutable output of agent execution.
 *
 * @param finalAnswer    the agent's final response text (null if not completed)
 * @param steps          history of all ReAct iterations
 * @param terminalState  the FSM state when execution ended
 * @param iterationsUsed number of think-act-observe cycles performed
 * @param totalDuration  wall-clock time of the entire execution
 */
public record AgentResult(
        String finalAnswer,
        List<AgentStepResult> steps,
        AgentState terminalState,
        int iterationsUsed,
        Duration totalDuration
) {

    public boolean isSuccess() {
        return terminalState == AgentState.COMPLETED;
    }
}
