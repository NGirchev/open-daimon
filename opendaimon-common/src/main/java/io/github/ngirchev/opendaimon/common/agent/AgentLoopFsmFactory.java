package io.github.ngirchev.opendaimon.common.agent;

import io.github.ngirchev.fsm.Action;
import io.github.ngirchev.fsm.FsmFactory;
import io.github.ngirchev.fsm.Guard;
import io.github.ngirchev.fsm.StateContext;
import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;

import java.util.function.Consumer;
import java.util.function.Predicate;

import static io.github.ngirchev.opendaimon.common.agent.AgentEvent.START;
import static io.github.ngirchev.opendaimon.common.agent.AgentState.*;

/**
 * Creates the ReAct agent loop FSM with all transitions defined declaratively.
 *
 * <p>The FSM uses auto-transitions: a single {@link AgentEvent#START} event
 * triggers the initial transition to THINKING, then the FSM automatically
 * chains through states based on guards until reaching a terminal state
 * (COMPLETED, FAILED, or MAX_ITERATIONS).
 *
 * <p>The OBSERVING -> THINKING cycle creates the ReAct loop. The
 * {@link AgentContext#isMaxIterationsReached()} guard prevents infinite loops.
 *
 * <p>Transition graph:
 * <pre>
 * INITIALIZED ──[START]──> THINKING
 *     action: think()
 *
 * THINKING ──[auto]──┬─[hasError]──────────────> FAILED (terminal)
 *                    │   action: handleError()
 *                    ├─[isMaxIterationsReached]─> MAX_ITERATIONS (terminal)
 *                    │   action: handleMaxIterations()
 *                    ├─[hasToolCall]───────────> TOOL_EXECUTING
 *                    │   action: executeTool()
 *                    ├─[hasFinalAnswer]────────> ANSWERING
 *                    │   action: answer()
 *                    ├─[canRetryEmptyResponse]─> THINKING (self-loop, single retry)
 *                    │   action: retryEmptyResponse()
 *                    └─[else]─────────────────> FAILED (terminal)
 *                        action: handleError()  (empty LLM output, retry exhausted)
 *
 * TOOL_EXECUTING ──[auto]──┬─[hasError]──> FAILED (terminal)
 *                          │   action: handleError()
 *                          └─[else]──────> OBSERVING
 *                              action: observe()
 *
 * OBSERVING ──[auto]──> THINKING (loop back)
 *     action: think()
 *
 * ANSWERING ──[auto]──> COMPLETED (terminal)
 * </pre>
 */
public final class AgentLoopFsmFactory {

    private AgentLoopFsmFactory() {
    }

    /**
     * Creates a stateless domain FSM for the ReAct agent loop.
     *
     * <p>The returned FSM is thread-safe and can be shared as a singleton Spring bean.
     * Each {@code handle(context, START)} call creates an internal FSM instance
     * scoped to that context.
     *
     * @param actions implementation of agent loop actions (injected by Spring)
     * @return domain FSM ready to process agent contexts
     */
    public static ExDomainFsm<AgentContext, AgentState, AgentEvent> create(
            AgentLoopActions actions) {

        var table = FsmFactory.INSTANCE.<AgentState, AgentEvent>statesWithEvents()
                .autoTransitionEnabled(true)

                // === INITIALIZED → THINKING (event-driven: START) ===
                .from(INITIALIZED).onEvent(START).to(THINKING)
                    .action(action(actions::think))
                    .end()

                // === THINKING → branch by LLM decision (auto-transition) ===
                .from(THINKING).toMultiple()
                    .to(FAILED)
                        .onCondition(guard(AgentContext::hasError))
                        .action(action(actions::handleError))
                        .end()
                    .to(MAX_ITERATIONS)
                        .onCondition(guard(AgentContext::isMaxIterationsReached))
                        .action(action(actions::handleMaxIterations))
                        .end()
                    .to(TOOL_EXECUTING)
                        .onCondition(guard(AgentContext::hasToolCall))
                        .action(action(actions::executeTool))
                        .end()
                    .to(ANSWERING)
                        .onCondition(guard(AgentContext::hasFinalAnswer))
                        .action(action(actions::answer))
                        .end()
                    .to(THINKING)
                        .onCondition(guard(AgentContext::canRetryEmptyResponse))
                        .action(action(actions::retryEmptyResponse))
                        .end()
                    .to(FAILED)
                        .action(action(actions::handleError))
                        .end()
                    .endMultiple()

                // === TOOL_EXECUTING → branch (auto-transition) ===
                .from(TOOL_EXECUTING).toMultiple()
                    .to(FAILED)
                        .onCondition(guard(AgentContext::hasError))
                        .action(action(actions::handleError))
                        .end()
                    .to(OBSERVING)
                        .action(action(actions::observe))
                        .end()
                    .endMultiple()

                // === OBSERVING → THINKING (auto-transition, loop back) ===
                .from(OBSERVING).to(THINKING)
                    .action(action(actions::think))
                    .end()

                // === ANSWERING → COMPLETED (auto-transition, terminal) ===
                .from(ANSWERING).to(COMPLETED)
                    .end()

                .build();

        return table.createDomainFsm();
    }

    /**
     * Adapts a typed predicate on {@link AgentContext} to a
     * {@link Guard} on {@code StateContext<AgentState>} required by the FSM library.
     */
    private static Guard<StateContext<AgentState>> guard(
            Predicate<AgentContext> predicate) {
        return ctx -> predicate.test((AgentContext) ctx);
    }

    /**
     * Adapts a typed consumer on {@link AgentContext} to an
     * {@link Action} on {@code StateContext<AgentState>} required by the FSM library.
     */
    private static Action<StateContext<AgentState>> action(
            Consumer<AgentContext> consumer) {
        return ctx -> consumer.accept((AgentContext) ctx);
    }
}
