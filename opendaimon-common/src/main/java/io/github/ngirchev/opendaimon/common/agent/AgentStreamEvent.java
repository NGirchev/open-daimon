package io.github.ngirchev.opendaimon.common.agent;

import java.time.Instant;

/**
 * An event emitted during streaming agent execution.
 *
 * <p>Allows consumers (UI, Telegram, REST) to show real-time progress
 * of the agent loop: thoughts, tool calls, observations, and the final answer.
 *
 * @param type      event type
 * @param content   event payload (thought text, tool name, observation, or final answer)
 * @param iteration current iteration number
 * @param timestamp when this event occurred
 * @param error     OBSERVATION-only flag: true when the tool threw and content carries the error summary
 */
public record AgentStreamEvent(
        EventType type,
        String content,
        int iteration,
        Instant timestamp,
        boolean error
) {

    public enum EventType {
        /** Agent is thinking — LLM call started. */
        THINKING,
        /** Agent decided to call a tool. Content = "toolName: args". */
        TOOL_CALL,
        /** Tool execution completed. Content = observation. */
        OBSERVATION,
        /**
         * Incremental delta of the final answer text streamed from the LLM.
         * Content is the new chunk only (not cumulative); consumers concatenate
         * to render the answer progressively. Emitted only for "outside"
         * fragments — content inside {@code <think>} or {@code <tool_call>}
         * blocks is filtered out at the source.
         */
        PARTIAL_ANSWER,
        /** Agent produced final answer. Content = answer text. */
        FINAL_ANSWER,
        /** Agent execution failed. Content = error message. */
        ERROR,
        /** Agent reached max iterations. Content = partial answer. */
        MAX_ITERATIONS,
        /** Agent metadata (e.g. model name). Content = metadata value. */
        METADATA
    }

    public static AgentStreamEvent thinking(int iteration) {
        return new AgentStreamEvent(EventType.THINKING, null, iteration, Instant.now(), false);
    }

    public static AgentStreamEvent thinking(String reasoningContent, int iteration) {
        return new AgentStreamEvent(EventType.THINKING, reasoningContent, iteration, Instant.now(), false);
    }

    public static AgentStreamEvent toolCall(String toolName, String args, int iteration) {
        return new AgentStreamEvent(EventType.TOOL_CALL, toolName + ": " + args, iteration, Instant.now(), false);
    }

    public static AgentStreamEvent observation(String observation, int iteration) {
        return new AgentStreamEvent(EventType.OBSERVATION, observation, iteration, Instant.now(), false);
    }

    public static AgentStreamEvent observation(String observation, boolean error, int iteration) {
        return new AgentStreamEvent(EventType.OBSERVATION, observation, iteration, Instant.now(), error);
    }

    public static AgentStreamEvent partialAnswer(String delta, int iteration) {
        return new AgentStreamEvent(EventType.PARTIAL_ANSWER, delta, iteration, Instant.now(), false);
    }

    public static AgentStreamEvent finalAnswer(String answer, int iteration) {
        return new AgentStreamEvent(EventType.FINAL_ANSWER, answer, iteration, Instant.now(), false);
    }

    public static AgentStreamEvent error(String error, int iteration) {
        return new AgentStreamEvent(EventType.ERROR, error, iteration, Instant.now(), false);
    }

    public static AgentStreamEvent maxIterations(String partialAnswer, int iteration) {
        return new AgentStreamEvent(EventType.MAX_ITERATIONS, partialAnswer, iteration, Instant.now(), false);
    }

    public static AgentStreamEvent metadata(String content, int iteration) {
        return new AgentStreamEvent(EventType.METADATA, content, iteration, Instant.now(), false);
    }
}
