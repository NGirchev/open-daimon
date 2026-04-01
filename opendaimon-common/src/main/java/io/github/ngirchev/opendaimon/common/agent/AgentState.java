package io.github.ngirchev.opendaimon.common.agent;

/**
 * States for the ReAct agent loop FSM.
 *
 * <p>Terminal states: {@link #COMPLETED}, {@link #FAILED}, {@link #MAX_ITERATIONS}.
 *
 * <p>Transition graph:
 * <pre>
 * INITIALIZED ──[START]──> THINKING
 *
 * THINKING ──[auto]──┬─[hasError]──────────────> FAILED (terminal)
 *                    ├─[isMaxIterationsReached]─> MAX_ITERATIONS (terminal)
 *                    ├─[hasToolCall]───────────> TOOL_EXECUTING
 *                    └─[hasFinalAnswer]────────> ANSWERING
 *
 * TOOL_EXECUTING ──[auto]──┬─[hasError]──> FAILED (terminal)
 *                          └─[else]──────> OBSERVING
 *
 * OBSERVING ──[auto]──> THINKING (loop back)
 *
 * ANSWERING ──[auto]──> COMPLETED (terminal)
 * </pre>
 */
public enum AgentState {

    /** Initial state — task received, agent loop not yet started. */
    INITIALIZED,

    /** LLM deciding next action: tool call or final answer. */
    THINKING,

    /** Executing a tool call returned by the LLM. */
    TOOL_EXECUTING,

    /** Processing tool result, preparing next iteration context. */
    OBSERVING,

    /** LLM generating final answer (no more tool calls). */
    ANSWERING,

    // --- Terminal states ---

    /** Final answer delivered successfully. */
    COMPLETED,

    /** Unrecoverable error during agent loop. */
    FAILED,

    /** Safety limit: maximum iterations reached without final answer. */
    MAX_ITERATIONS
}
