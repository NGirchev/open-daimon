package io.github.ngirchev.opendaimon.common.agent.orchestration;

import java.time.Duration;
import java.util.List;

/**
 * Result of a complete orchestration plan execution.
 *
 * @param planName      name of the executed plan
 * @param status        overall execution status
 * @param stepResults   results of each step in execution order
 * @param totalDuration wall-clock time for the entire plan
 */
public record OrchestrationResult(
        String planName,
        OrchestrationStatus status,
        List<StepResult> stepResults,
        Duration totalDuration
) {

    public enum OrchestrationStatus {
        COMPLETED,
        PARTIALLY_COMPLETED,
        FAILED
    }

    public boolean isSuccess() {
        return status == OrchestrationStatus.COMPLETED;
    }

    /**
     * Returns the output of the last successfully completed step,
     * which is typically the final answer of the orchestration.
     */
    public String getFinalOutput() {
        return stepResults.stream()
                .filter(StepResult::isSuccess)
                .reduce((first, second) -> second)
                .map(StepResult::output)
                .orElse(null);
    }
}
