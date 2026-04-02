package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.agent.orchestration.AgentOrchestrator;
import io.github.ngirchev.opendaimon.common.agent.orchestration.OrchestrationPlan;
import io.github.ngirchev.opendaimon.common.agent.orchestration.OrchestrationResult;
import io.github.ngirchev.opendaimon.common.agent.orchestration.StepResult;
import io.github.ngirchev.opendaimon.common.agent.persistence.AgentExecutionEntity;
import io.github.ngirchev.opendaimon.common.agent.persistence.AgentExecutionRepository;
import io.github.ngirchev.opendaimon.common.agent.persistence.AgentExecutionStepEntity;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/**
 * Decorator that persists orchestration execution and step results to the database.
 *
 * <p>Wraps a delegate {@link AgentOrchestrator}, executing the plan through it
 * and saving the results. This keeps the core orchestrator logic clean and
 * persistence optional (enabled only when the repository bean is available).
 */
@Slf4j
public class PersistingAgentOrchestrator implements AgentOrchestrator {

    private final AgentOrchestrator delegate;
    private final AgentExecutionRepository repository;

    public PersistingAgentOrchestrator(AgentOrchestrator delegate, AgentExecutionRepository repository) {
        this.delegate = delegate;
        this.repository = repository;
    }

    @Override
    public OrchestrationResult execute(OrchestrationPlan plan) {
        AgentExecutionEntity execution = createExecution(plan);
        try {
            execution = repository.save(execution);
        } catch (Exception e) {
            log.warn("Failed to persist agent execution start: {}", e.getMessage());
        }

        OrchestrationResult result = delegate.execute(plan);

        try {
            updateExecution(execution, result);
            repository.save(execution);
            log.info("Agent execution persisted: id={}, status={}", execution.getId(), execution.getStatus());
        } catch (Exception e) {
            log.warn("Failed to persist agent execution result: {}", e.getMessage());
        }

        return result;
    }

    private AgentExecutionEntity createExecution(OrchestrationPlan plan) {
        AgentExecutionEntity entity = new AgentExecutionEntity();
        entity.setPlanName(plan.name());
        entity.setConversationId(plan.conversationId());
        entity.setStatus(AgentExecutionEntity.ExecutionStatus.RUNNING);
        entity.setTotalSteps(plan.steps().size());
        entity.setStartedAt(Instant.now());
        return entity;
    }

    private void updateExecution(AgentExecutionEntity execution, OrchestrationResult result) {
        execution.setStatus(mapStatus(result.status()));
        execution.setFinishedAt(Instant.now());
        execution.setDurationMs(result.totalDuration().toMillis());
        execution.setFinalOutput(result.getFinalOutput());

        int completed = 0;
        int failed = 0;

        for (StepResult stepResult : result.stepResults()) {
            AgentExecutionStepEntity stepEntity = new AgentExecutionStepEntity();
            stepEntity.setExecution(execution);
            stepEntity.setStepId(stepResult.stepId());
            stepEntity.setStepName(stepResult.stepName());
            stepEntity.setTask("");
            stepEntity.setStatus(mapStepStatus(stepResult.status()));
            stepEntity.setOutput(stepResult.output());
            stepEntity.setErrorMessage(stepResult.error());
            stepEntity.setIterationsUsed(stepResult.agentResult() != null
                    ? stepResult.agentResult().iterationsUsed() : 0);
            Instant stepFinished = Instant.now();
            Instant stepStarted = stepFinished.minus(stepResult.duration());
            stepEntity.setStartedAt(stepStarted);
            stepEntity.setFinishedAt(stepFinished);
            stepEntity.setDurationMs(stepResult.duration().toMillis());

            execution.getSteps().add(stepEntity);

            if (stepResult.isSuccess()) {
                completed++;
            } else {
                failed++;
            }
        }

        execution.setCompletedSteps(completed);
        execution.setFailedSteps(failed);
    }

    private AgentExecutionEntity.ExecutionStatus mapStatus(OrchestrationResult.OrchestrationStatus status) {
        return switch (status) {
            case COMPLETED -> AgentExecutionEntity.ExecutionStatus.COMPLETED;
            case PARTIALLY_COMPLETED -> AgentExecutionEntity.ExecutionStatus.PARTIALLY_COMPLETED;
            case FAILED -> AgentExecutionEntity.ExecutionStatus.FAILED;
        };
    }

    private AgentExecutionStepEntity.StepStatus mapStepStatus(StepResult.StepStatus status) {
        return switch (status) {
            case COMPLETED -> AgentExecutionStepEntity.StepStatus.COMPLETED;
            case FAILED -> AgentExecutionStepEntity.StepStatus.FAILED;
            case SKIPPED -> AgentExecutionStepEntity.StepStatus.SKIPPED;
        };
    }
}
