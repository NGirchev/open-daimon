package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentResult;
import io.github.ngirchev.opendaimon.common.agent.orchestration.AgentOrchestrator;
import io.github.ngirchev.opendaimon.common.agent.orchestration.OrchestrationPlan;
import io.github.ngirchev.opendaimon.common.agent.orchestration.OrchestrationResult;
import io.github.ngirchev.opendaimon.common.agent.orchestration.OrchestrationStep;
import io.github.ngirchev.opendaimon.common.agent.orchestration.StepResult;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Default orchestrator that executes plan steps sequentially in dependency order.
 *
 * <p>Steps are executed one by one. Each step receives the outputs of its
 * dependency steps as context appended to the task description.
 * If a step fails, dependent steps are skipped.
 *
 * <p>Error recovery: individual step failures don't abort the entire plan —
 * only steps that depend on the failed step are skipped. Independent steps
 * continue executing.
 */
@Slf4j
public class DefaultAgentOrchestrator implements AgentOrchestrator {

    private final AgentExecutor agentExecutor;
    private final int defaultMaxIterations;

    public DefaultAgentOrchestrator(AgentExecutor agentExecutor, int defaultMaxIterations) {
        this.agentExecutor = agentExecutor;
        this.defaultMaxIterations = defaultMaxIterations;
    }

    @Override
    public OrchestrationResult execute(OrchestrationPlan plan) {
        Instant start = Instant.now();
        log.info("Orchestration started: plan='{}', steps={}", plan.name(), plan.steps().size());

        Map<String, StepResult> completedSteps = new HashMap<>();
        Set<String> failedStepIds = new HashSet<>();
        List<StepResult> stepResults = new ArrayList<>();

        List<OrchestrationStep> executionOrder = resolveExecutionOrder(plan.steps());

        for (OrchestrationStep step : executionOrder) {
            if (shouldSkip(step, failedStepIds)) {
                StepResult skipped = StepResult.skipped(step.id(), step.name(),
                        "Skipped: dependency failed");
                stepResults.add(skipped);
                failedStepIds.add(step.id());
                log.info("Orchestration step skipped: step='{}' (dependency failed)", step.name());
                continue;
            }

            StepResult result = executeStep(step, plan.conversationId(), completedSteps);
            stepResults.add(result);
            completedSteps.put(step.id(), result);

            if (!result.isSuccess()) {
                failedStepIds.add(step.id());
                log.warn("Orchestration step failed: step='{}', error='{}'",
                        step.name(), result.error());
            } else {
                log.info("Orchestration step completed: step='{}', outputLength={}",
                        step.name(), result.output() != null ? result.output().length() : 0);
            }
        }

        Duration totalDuration = Duration.between(start, Instant.now());
        OrchestrationResult.OrchestrationStatus status = determineStatus(stepResults);

        log.info("Orchestration finished: plan='{}', status={}, duration={}ms",
                plan.name(), status, totalDuration.toMillis());

        return new OrchestrationResult(plan.name(), status, List.copyOf(stepResults), totalDuration);
    }

    private StepResult executeStep(OrchestrationStep step, String conversationId,
                                   Map<String, StepResult> completedSteps) {
        Instant stepStart = Instant.now();
        try {
            String enrichedTask = buildEnrichedTask(step, completedSteps);
            int maxIterations = step.maxIterations() != null
                    ? step.maxIterations()
                    : defaultMaxIterations;

            // Orchestration steps are textual plan decompositions — they do not inherit user
            // image attachments (mirrors the PlanAndExecuteAgentExecutor decision). The 5-arg
            // ctor resolves attachments to List.of(); see docs/usecases/agent-image-attachment.md.
            AgentRequest request = new AgentRequest(
                    enrichedTask,
                    conversationId,
                    step.params(),
                    maxIterations,
                    Set.of()
            );

            log.info("Orchestration executing step: step='{}', maxIterations={}",
                    step.name(), maxIterations);

            AgentResult agentResult = agentExecutor.execute(request);
            Duration stepDuration = Duration.between(stepStart, Instant.now());

            if (agentResult.isSuccess()) {
                return StepResult.success(step.id(), step.name(),
                        agentResult.finalAnswer(), agentResult, stepDuration);
            } else {
                String error = "Agent finished in state: " + agentResult.terminalState();
                return StepResult.failure(step.id(), step.name(), error, stepDuration);
            }
        } catch (Exception e) {
            Duration stepDuration = Duration.between(stepStart, Instant.now());
            log.error("Orchestration step threw exception: step='{}', error='{}'",
                    step.name(), e.getMessage(), e);
            return StepResult.failure(step.id(), step.name(), e.getMessage(), stepDuration);
        }
    }

    /**
     * Builds the task string for a step, enriched with outputs from dependency steps.
     */
    private String buildEnrichedTask(OrchestrationStep step, Map<String, StepResult> completedSteps) {
        if (!step.hasDependencies()) {
            return step.task();
        }

        var sb = new StringBuilder(step.task());
        sb.append("\n\nContext from previous steps:\n");

        for (String depId : step.dependsOn()) {
            StepResult depResult = completedSteps.get(depId);
            if (depResult != null && depResult.isSuccess() && depResult.output() != null) {
                sb.append("\n--- ").append(depResult.stepName()).append(" ---\n");
                sb.append(depResult.output()).append('\n');
            }
        }

        return sb.toString();
    }

    /**
     * Checks if a step should be skipped due to failed dependencies.
     */
    private boolean shouldSkip(OrchestrationStep step, Set<String> failedStepIds) {
        if (!step.hasDependencies()) {
            return false;
        }
        return step.dependsOn().stream().anyMatch(failedStepIds::contains);
    }

    /**
     * Resolves execution order using Kahn's algorithm (topological sort).
     *
     * <p>Steps with no dependencies come first. Steps whose dependencies
     * are all resolved come next, and so on. Throws if a cycle is detected.
     */
    private List<OrchestrationStep> resolveExecutionOrder(List<OrchestrationStep> steps) {
        Map<String, OrchestrationStep> stepById = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();

        for (OrchestrationStep step : steps) {
            stepById.put(step.id(), step);
            inDegree.put(step.id(), step.dependsOn() != null ? step.dependsOn().size() : 0);
            dependents.putIfAbsent(step.id(), new ArrayList<>());
            if (step.dependsOn() != null) {
                for (String dep : step.dependsOn()) {
                    dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(step.id());
                }
            }
        }

        // Start with steps that have no dependencies
        Queue<String> ready = new ArrayDeque<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<OrchestrationStep> sorted = new ArrayList<>();
        while (!ready.isEmpty()) {
            String current = ready.poll();
            OrchestrationStep step = stepById.get(current);
            if (step != null) {
                sorted.add(step);
            }
            for (String dependent : dependents.getOrDefault(current, List.of())) {
                int remaining = inDegree.merge(dependent, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (sorted.size() != steps.size()) {
            throw new IllegalArgumentException(
                    "Orchestration plan has a dependency cycle. Sorted " + sorted.size()
                            + " of " + steps.size() + " steps.");
        }

        return sorted;
    }

    private OrchestrationResult.OrchestrationStatus determineStatus(List<StepResult> results) {
        boolean allSuccess = results.stream().allMatch(StepResult::isSuccess);
        if (allSuccess) {
            return OrchestrationResult.OrchestrationStatus.COMPLETED;
        }
        boolean anySuccess = results.stream().anyMatch(StepResult::isSuccess);
        if (anySuccess) {
            return OrchestrationResult.OrchestrationStatus.PARTIALLY_COMPLETED;
        }
        return OrchestrationResult.OrchestrationStatus.FAILED;
    }
}
