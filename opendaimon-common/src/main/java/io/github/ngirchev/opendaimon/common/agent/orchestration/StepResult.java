package io.github.ngirchev.opendaimon.common.agent.orchestration;

import io.github.ngirchev.opendaimon.common.agent.AgentResult;

import java.time.Duration;

/**
 * Result of a single orchestration step execution.
 *
 * @param stepId      the step that was executed
 * @param stepName    human-readable step name
 * @param status      execution status
 * @param output      agent's final answer (null on failure)
 * @param error       error message (null on success)
 * @param agentResult full agent execution result (for detailed step history)
 * @param duration    wall-clock time for this step
 */
public record StepResult(
        String stepId,
        String stepName,
        StepStatus status,
        String output,
        String error,
        AgentResult agentResult,
        Duration duration
) {

    public enum StepStatus {
        COMPLETED,
        FAILED,
        SKIPPED
    }

    public boolean isSuccess() {
        return status == StepStatus.COMPLETED;
    }

    public static StepResult success(String stepId, String stepName, String output,
                                     AgentResult agentResult, Duration duration) {
        return new StepResult(stepId, stepName, StepStatus.COMPLETED, output, null, agentResult, duration);
    }

    public static StepResult failure(String stepId, String stepName, String error, Duration duration) {
        return new StepResult(stepId, stepName, StepStatus.FAILED, null, error, null, duration);
    }

    public static StepResult skipped(String stepId, String stepName, String reason) {
        return new StepResult(stepId, stepName, StepStatus.SKIPPED, null, reason, null, Duration.ZERO);
    }
}
