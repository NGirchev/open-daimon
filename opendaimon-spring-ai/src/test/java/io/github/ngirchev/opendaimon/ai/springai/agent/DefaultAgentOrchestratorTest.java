package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentResult;
import io.github.ngirchev.opendaimon.common.agent.AgentState;
import io.github.ngirchev.opendaimon.common.agent.orchestration.OrchestrationPlan;
import io.github.ngirchev.opendaimon.common.agent.orchestration.OrchestrationResult;
import io.github.ngirchev.opendaimon.common.agent.orchestration.OrchestrationStep;
import io.github.ngirchev.opendaimon.common.agent.orchestration.StepResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAgentOrchestratorTest {

    @Mock
    private AgentExecutor agentExecutor;

    private DefaultAgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new DefaultAgentOrchestrator(agentExecutor, 10);
    }

    @Test
    @DisplayName("Single step plan executes successfully")
    void singleStep_succeeds() {
        when(agentExecutor.execute(any())).thenReturn(successResult("Result of step 1"));

        OrchestrationPlan plan = new OrchestrationPlan("test-plan", "conv-1", List.of(
                new OrchestrationStep("s1", "Step 1", "Do something")
        ));

        OrchestrationResult result = orchestrator.execute(plan);

        assertEquals(OrchestrationResult.OrchestrationStatus.COMPLETED, result.status());
        assertEquals(1, result.stepResults().size());
        assertEquals("Result of step 1", result.getFinalOutput());
        verify(agentExecutor, times(1)).execute(any());
    }

    @Test
    @DisplayName("Multi-step plan with dependencies passes context between steps")
    void multiStep_withDependencies_passesContext() {
        when(agentExecutor.execute(any()))
                .thenReturn(successResult("Research findings"))
                .thenReturn(successResult("Summary of findings"));

        OrchestrationPlan plan = new OrchestrationPlan("research-plan", "conv-1", List.of(
                new OrchestrationStep("research", "Research", "Find info about Java 21"),
                new OrchestrationStep("summarize", "Summarize", "Summarize the research",
                        List.of("research"))
        ));

        OrchestrationResult result = orchestrator.execute(plan);

        assertEquals(OrchestrationResult.OrchestrationStatus.COMPLETED, result.status());
        assertEquals(2, result.stepResults().size());
        assertTrue(result.stepResults().get(0).isSuccess());
        assertTrue(result.stepResults().get(1).isSuccess());
        assertEquals("Summary of findings", result.getFinalOutput());
        verify(agentExecutor, times(2)).execute(any());
    }

    @Test
    @DisplayName("Failed step causes dependent steps to be skipped")
    void failedStep_skipsDependents() {
        when(agentExecutor.execute(any()))
                .thenReturn(failedResult());

        OrchestrationPlan plan = new OrchestrationPlan("failing-plan", "conv-1", List.of(
                new OrchestrationStep("s1", "Step 1", "This will fail"),
                new OrchestrationStep("s2", "Step 2", "Depends on s1", List.of("s1"))
        ));

        OrchestrationResult result = orchestrator.execute(plan);

        assertEquals(OrchestrationResult.OrchestrationStatus.FAILED, result.status());
        assertEquals(2, result.stepResults().size());
        assertEquals(StepResult.StepStatus.FAILED, result.stepResults().get(0).status());
        assertEquals(StepResult.StepStatus.SKIPPED, result.stepResults().get(1).status());
        verify(agentExecutor, times(1)).execute(any());
    }

    @Test
    @DisplayName("Independent steps continue even if one fails")
    void independentSteps_continueOnFailure() {
        when(agentExecutor.execute(any()))
                .thenReturn(failedResult())
                .thenReturn(successResult("Step 2 result"));

        OrchestrationPlan plan = new OrchestrationPlan("mixed-plan", "conv-1", List.of(
                new OrchestrationStep("s1", "Step 1", "This will fail"),
                new OrchestrationStep("s2", "Step 2", "Independent step")
        ));

        OrchestrationResult result = orchestrator.execute(plan);

        assertEquals(OrchestrationResult.OrchestrationStatus.PARTIALLY_COMPLETED, result.status());
        assertEquals(2, result.stepResults().size());
        assertEquals(StepResult.StepStatus.FAILED, result.stepResults().get(0).status());
        assertEquals(StepResult.StepStatus.COMPLETED, result.stepResults().get(1).status());
    }

    @Test
    @DisplayName("Orchestration result has valid duration")
    void orchestration_hasDuration() {
        when(agentExecutor.execute(any())).thenReturn(successResult("Done"));

        OrchestrationPlan plan = new OrchestrationPlan("timed-plan", "conv-1", List.of(
                new OrchestrationStep("s1", "Step 1", "Quick task")
        ));

        OrchestrationResult result = orchestrator.execute(plan);

        assertNotNull(result.totalDuration());
        assertTrue(result.totalDuration().toMillis() >= 0);
    }

    @Test
    @DisplayName("Topological sort reorders steps correctly")
    void topologicalSort_reordersSteps() {
        when(agentExecutor.execute(any()))
                .thenReturn(successResult("A done"))
                .thenReturn(successResult("B done"))
                .thenReturn(successResult("C done"));

        // Steps given in reverse dependency order — orchestrator should reorder
        OrchestrationPlan plan = new OrchestrationPlan("topo-plan", "conv-1", List.of(
                new OrchestrationStep("c", "Step C", "Final step", List.of("a", "b")),
                new OrchestrationStep("a", "Step A", "First step"),
                new OrchestrationStep("b", "Step B", "Second step", List.of("a"))
        ));

        OrchestrationResult result = orchestrator.execute(plan);

        assertEquals(OrchestrationResult.OrchestrationStatus.COMPLETED, result.status());
        assertEquals(3, result.stepResults().size());
        // A must come first, B second (depends on A), C last (depends on A and B)
        assertEquals("Step A", result.stepResults().get(0).stepName());
        assertEquals("Step B", result.stepResults().get(1).stepName());
        assertEquals("Step C", result.stepResults().get(2).stepName());
    }

    @Test
    @DisplayName("Cyclic dependencies throw IllegalArgumentException")
    void cyclicDependencies_throws() {
        OrchestrationPlan plan = new OrchestrationPlan("cycle-plan", "conv-1", List.of(
                new OrchestrationStep("a", "Step A", "Task A", List.of("b")),
                new OrchestrationStep("b", "Step B", "Task B", List.of("a"))
        ));

        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.execute(plan));
    }

    private AgentResult successResult(String answer) {
        return new AgentResult(answer, List.of(), AgentState.COMPLETED, 1, Duration.ofMillis(100));
    }

    private AgentResult failedResult() {
        return new AgentResult(null, List.of(), AgentState.FAILED, 0, Duration.ofMillis(50));
    }
}
