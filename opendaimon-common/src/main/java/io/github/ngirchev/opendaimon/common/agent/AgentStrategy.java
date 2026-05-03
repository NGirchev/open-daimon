package io.github.ngirchev.opendaimon.common.agent;

/**
 * Available agent execution strategies.
 *
 * <p>Each strategy determines how the agent processes a task:
 * <ul>
 *   <li>{@link #REACT} — iterative think-act-observe loop with tool calling</li>
 *   <li>{@link #SIMPLE} — single LLM call without tools (fast, no loop)</li>
 *   <li>{@link #PLAN_AND_EXECUTE} — LLM plans steps first, then executes each with ReAct</li>
 *   <li>{@link #AUTO} — automatically selects the best strategy based on context</li>
 * </ul>
 */
public enum AgentStrategy {

    /** ReAct loop: THINKING → TOOL_EXECUTING → OBSERVING → repeat. Default for tasks with tools. */
    REACT,

    /** Single LLM call without tools. Fast path for simple questions. */
    SIMPLE,

    /** LLM generates a plan, then each step is executed with ReAct. For complex multi-step tasks. */
    PLAN_AND_EXECUTE,

    /** Auto-select strategy based on task and available tools. */
    AUTO
}
