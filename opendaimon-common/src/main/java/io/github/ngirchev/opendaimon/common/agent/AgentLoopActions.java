package io.github.ngirchev.opendaimon.common.agent;

/**
 * SPI for agent loop business logic.
 *
 * <p>Each method corresponds to an FSM action invoked during a specific state transition.
 * Implementations are responsible for populating the {@link AgentContext} with results
 * that drive subsequent transitions via guard conditions.
 *
 * <p>The default implementation for Spring AI is {@code SpringAgentLoopActions}
 * in the {@code opendaimon-spring-ai} module.
 */
public interface AgentLoopActions {

    /**
     * INITIALIZED -> THINKING or OBSERVING -> THINKING.
     *
     * <p>Calls the LLM with the current context (task, step history, available tools).
     * Must populate one of:
     * <ul>
     *   <li>{@link AgentContext#setCurrentToolName} + {@link AgentContext#setCurrentToolArguments}
     *       if the LLM chose a tool call</li>
     *   <li>{@link AgentContext#setCurrentTextResponse} if the LLM produced a final answer</li>
     *   <li>{@link AgentContext#setErrorMessage} if the LLM call failed</li>
     * </ul>
     */
    void think(AgentContext ctx);

    /**
     * THINKING -> TOOL_EXECUTING.
     *
     * <p>Executes the tool identified by {@link AgentContext#getCurrentToolName()}.
     * Must populate {@link AgentContext#setToolResult} with the execution outcome.
     * On failure, may set {@link AgentContext#setErrorMessage} instead.
     */
    void executeTool(AgentContext ctx);

    /**
     * TOOL_EXECUTING -> OBSERVING.
     *
     * <p>Processes the tool result: records the step in history,
     * increments iteration counter, and resets per-iteration state
     * so the next THINKING phase starts clean.
     */
    void observe(AgentContext ctx);

    /**
     * THINKING -> ANSWERING.
     *
     * <p>Extracts the final answer from LLM response and sets it
     * on {@link AgentContext#setFinalAnswer}.
     */
    void answer(AgentContext ctx);

    /**
     * THINKING -> MAX_ITERATIONS (terminal).
     *
     * <p>Handles the case when the agent exhausted its iteration budget.
     * Should produce a best-effort answer from accumulated observations
     * and set it on {@link AgentContext#setFinalAnswer}.
     */
    void handleMaxIterations(AgentContext ctx);

    /**
     * Any state -> FAILED (terminal).
     *
     * <p>Handles unrecoverable errors. Error message is already set
     * on the context; this action can perform cleanup or logging.
     */
    void handleError(AgentContext ctx);

    /**
     * THINKING -> THINKING (self-loop, single retry).
     *
     * <p>Invoked when the LLM returned an empty response (no tool call, no text,
     * no error) and {@link AgentContext#canRetryEmptyResponse()} is true.
     * Implementations should increment the retry counter, clear the empty-response
     * flag, optionally nudge the model (e.g. insert a SystemMessage), and re-invoke
     * {@link #think(AgentContext)}.
     */
    default void retryEmptyResponse(AgentContext ctx) {
        ctx.incrementEmptyResponseRetryCount();
        ctx.clearEmptyResponse();
        think(ctx);
    }
}
