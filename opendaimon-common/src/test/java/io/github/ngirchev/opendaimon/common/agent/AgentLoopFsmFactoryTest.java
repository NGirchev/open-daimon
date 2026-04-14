package io.github.ngirchev.opendaimon.common.agent;

import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopFsmFactoryTest {

    private ExDomainFsm<AgentContext, AgentState, AgentEvent> fsm;

    /**
     * Callback that controls what think() does. Set before running the FSM.
     */
    private java.util.function.Consumer<AgentContext> thinkBehavior;

    private final AgentLoopActions testActions = new AgentLoopActions() {

        @Override
        public void think(AgentContext ctx) {
            if (thinkBehavior != null) {
                thinkBehavior.accept(ctx);
            }
        }

        @Override
        public void executeTool(AgentContext ctx) {
            ctx.setToolResult(AgentToolResult.success(ctx.getCurrentToolName(), "tool-output"));
        }

        @Override
        public void observe(AgentContext ctx) {
            ctx.recordStep(new AgentStepResult(
                    ctx.getCurrentIteration(),
                    ctx.getCurrentThought(),
                    ctx.getCurrentToolName(),
                    ctx.getCurrentToolArguments(),
                    ctx.getToolResult() != null ? ctx.getToolResult().result() : null,
                    Instant.now()
            ));
            ctx.incrementIteration();
            ctx.resetIterationState();
        }

        @Override
        public void answer(AgentContext ctx) {
            ctx.setFinalAnswer(ctx.getCurrentTextResponse());
        }

        @Override
        public void handleMaxIterations(AgentContext ctx) {
            ctx.setFinalAnswer("Max iterations reached after " + ctx.getCurrentIteration() + " cycles");
        }

        @Override
        public void handleError(AgentContext ctx) {
            // Error message is already set on context
        }
    };

    @BeforeEach
    void setUp() {
        fsm = AgentLoopFsmFactory.create(testActions);
        thinkBehavior = null;
    }

    @Test
    @DisplayName("Direct answer: INITIALIZED -> THINKING -> ANSWERING -> COMPLETED")
    void directAnswer_completesWithoutToolCalls() {
        thinkBehavior = ctx -> {
            ctx.setCurrentThought("I know the answer");
            ctx.setCurrentTextResponse("The answer is 42");
        };

        AgentContext ctx = createContext(10);
        fsm.handle(ctx, AgentEvent.START);

        assertEquals(AgentState.COMPLETED, ctx.getState());
        assertEquals("The answer is 42", ctx.getFinalAnswer());
        assertEquals(0, ctx.getCurrentIteration());
        assertTrue(ctx.getStepHistory().isEmpty());
    }

    @Test
    @DisplayName("Single tool call: THINKING -> TOOL -> OBSERVING -> THINKING -> ANSWERING -> COMPLETED")
    void singleToolCall_completesAfterOneIteration() {
        var callCount = new int[]{0};
        thinkBehavior = ctx -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                ctx.setCurrentThought("I need to search");
                ctx.setCurrentToolName("web_search");
                ctx.setCurrentToolArguments("{\"query\":\"java 21\"}");
            } else {
                ctx.setCurrentThought("Now I can answer");
                ctx.setCurrentTextResponse("Java 21 has virtual threads");
            }
        };

        AgentContext ctx = createContext(10);
        fsm.handle(ctx, AgentEvent.START);

        assertEquals(AgentState.COMPLETED, ctx.getState());
        assertEquals("Java 21 has virtual threads", ctx.getFinalAnswer());
        assertEquals(1, ctx.getCurrentIteration());
        assertEquals(1, ctx.getStepHistory().size());
        assertEquals("web_search", ctx.getStepHistory().getFirst().action());
    }

    @Test
    @DisplayName("Multiple tool calls: cycles through THINKING -> TOOL -> OBSERVE multiple times")
    void multipleToolCalls_cyclesCorrectly() {
        var callCount = new int[]{0};
        thinkBehavior = ctx -> {
            callCount[0]++;
            if (callCount[0] <= 3) {
                ctx.setCurrentThought("Need tool " + callCount[0]);
                ctx.setCurrentToolName("tool_" + callCount[0]);
                ctx.setCurrentToolArguments("{}");
            } else {
                ctx.setCurrentThought("Done");
                ctx.setCurrentTextResponse("Final answer after 3 tools");
            }
        };

        AgentContext ctx = createContext(10);
        fsm.handle(ctx, AgentEvent.START);

        assertEquals(AgentState.COMPLETED, ctx.getState());
        assertEquals(3, ctx.getCurrentIteration());
        assertEquals(3, ctx.getStepHistory().size());
        assertEquals("tool_1", ctx.getStepHistory().get(0).action());
        assertEquals("tool_2", ctx.getStepHistory().get(1).action());
        assertEquals("tool_3", ctx.getStepHistory().get(2).action());
    }

    @Test
    @DisplayName("Max iterations guard prevents infinite loop")
    void maxIterationsReached_terminatesGracefully() {
        thinkBehavior = ctx -> {
            ctx.setCurrentThought("Need more tools");
            ctx.setCurrentToolName("infinite_tool");
            ctx.setCurrentToolArguments("{}");
        };

        AgentContext ctx = createContext(3);
        fsm.handle(ctx, AgentEvent.START);

        assertEquals(AgentState.MAX_ITERATIONS, ctx.getState());
        assertEquals(3, ctx.getCurrentIteration());
        assertTrue(ctx.getFinalAnswer().contains("Max iterations reached"));
    }

    @Test
    @DisplayName("Error during think transitions to FAILED")
    void errorDuringThink_transitionsToFailed() {
        thinkBehavior = ctx -> ctx.setErrorMessage("LLM call failed: timeout");

        AgentContext ctx = createContext(10);
        fsm.handle(ctx, AgentEvent.START);

        assertEquals(AgentState.FAILED, ctx.getState());
        assertEquals("LLM call failed: timeout", ctx.getErrorMessage());
    }

    @Test
    @DisplayName("Error during tool execution transitions to FAILED")
    void errorDuringToolExecution_transitionsToFailed() {
        thinkBehavior = ctx -> {
            ctx.setCurrentThought("Let me try this tool");
            ctx.setCurrentToolName("broken_tool");
            ctx.setCurrentToolArguments("{}");
        };

        AgentLoopActions errorActions = new DelegatingAgentLoopActions(testActions) {
            @Override
            public void executeTool(AgentContext ctx) {
                ctx.setErrorMessage("Tool execution failed: broken_tool not found");
            }
        };

        var errorFsm = AgentLoopFsmFactory.create(errorActions);
        AgentContext ctx = createContext(10);
        errorFsm.handle(ctx, AgentEvent.START);

        assertEquals(AgentState.FAILED, ctx.getState());
        assertTrue(ctx.getErrorMessage().contains("broken_tool not found"));
    }

    @Test
    @DisplayName("Zero max iterations immediately triggers MAX_ITERATIONS on first think")
    void zeroMaxIterations_immediatelyTerminates() {
        thinkBehavior = ctx -> {
            // Think produces nothing — but maxIterations guard fires before hasToolCall/hasFinalAnswer
        };

        AgentContext ctx = createContext(0);
        fsm.handle(ctx, AgentEvent.START);

        assertEquals(AgentState.MAX_ITERATIONS, ctx.getState());
    }

    @Test
    @DisplayName("Event on terminal COMPLETED state throws exception")
    void eventOnTerminalCompleted_throws() {
        thinkBehavior = ctx -> {
            ctx.setCurrentThought("Direct answer");
            ctx.setCurrentTextResponse("42");
        };

        AgentContext ctx = createContext(10);
        fsm.handle(ctx, AgentEvent.START);
        assertEquals(AgentState.COMPLETED, ctx.getState());

        // Fire START again on terminal state — FSM rejects illegal transition
        assertThrows(Exception.class, () -> fsm.handle(ctx, AgentEvent.START));
    }

    @Test
    @DisplayName("Event on terminal FAILED state throws exception")
    void eventOnTerminalFailed_throws() {
        thinkBehavior = ctx -> ctx.setErrorMessage("fail");

        AgentContext ctx = createContext(10);
        fsm.handle(ctx, AgentEvent.START);
        assertEquals(AgentState.FAILED, ctx.getState());

        assertThrows(Exception.class, () -> fsm.handle(ctx, AgentEvent.START));
    }

    @Test
    @DisplayName("Event on terminal MAX_ITERATIONS state throws exception")
    void eventOnTerminalMaxIterations_throws() {
        thinkBehavior = ctx -> {
            ctx.setCurrentThought("Need more tools");
            ctx.setCurrentToolName("infinite_tool");
            ctx.setCurrentToolArguments("{}");
        };

        AgentContext ctx = createContext(1);
        fsm.handle(ctx, AgentEvent.START);
        assertEquals(AgentState.MAX_ITERATIONS, ctx.getState());

        assertThrows(Exception.class, () -> fsm.handle(ctx, AgentEvent.START));
    }

    private AgentContext createContext(int maxIterations) {
        return new AgentContext("test task", "conv-1", Map.of(), maxIterations, Set.of());
    }

    /**
     * Delegating wrapper for overriding specific actions in tests.
     */
    private static class DelegatingAgentLoopActions implements AgentLoopActions {
        private final AgentLoopActions delegate;

        DelegatingAgentLoopActions(AgentLoopActions delegate) {
            this.delegate = delegate;
        }

        @Override
        public void think(AgentContext ctx) {
            delegate.think(ctx);
        }

        @Override
        public void executeTool(AgentContext ctx) {
            delegate.executeTool(ctx);
        }

        @Override
        public void observe(AgentContext ctx) {
            delegate.observe(ctx);
        }

        @Override
        public void answer(AgentContext ctx) {
            delegate.answer(ctx);
        }

        @Override
        public void handleMaxIterations(AgentContext ctx) {
            delegate.handleMaxIterations(ctx);
        }

        @Override
        public void handleError(AgentContext ctx) {
            delegate.handleError(ctx);
        }
    }
}
