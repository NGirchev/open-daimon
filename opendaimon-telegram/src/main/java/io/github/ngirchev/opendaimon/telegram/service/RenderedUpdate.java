package io.github.ngirchev.opendaimon.telegram.service;

/**
 * A pure, side-effect-free description of what the Telegram orchestrator should do
 * in response to an {@code AgentStreamEvent}. Produced by
 * {@link TelegramAgentStreamRenderer#render}; consumed by the message handler action
 * that performs the actual {@code sendMessage}/{@code editMessage}/{@code deleteMessage}
 * calls.
 *
 * <p>Separating "what to change" (this interface) from "how to change it"
 * (the orchestrator) keeps the renderer synchronous and trivially unit-testable.
 */
public sealed interface RenderedUpdate
        permits RenderedUpdate.ReplaceTrailingThinkingLine,
                RenderedUpdate.AppendFreshThinking,
                RenderedUpdate.AppendToolCall,
                RenderedUpdate.AppendObservation,
                RenderedUpdate.AppendErrorToStatus,
                RenderedUpdate.RollbackAndAppendToolCall,
                RenderedUpdate.NoOp {

    /**
     * Replace the trailing {@code 💭 Thinking...} / reasoning overlay line in the status
     * buffer with this reasoning snippet. Used for in-place updates of the reasoning line.
     */
    record ReplaceTrailingThinkingLine(String reasoning) implements RenderedUpdate {}

    /** Append a fresh {@code 💭 Thinking...} line at the end of the status buffer. */
    record AppendFreshThinking() implements RenderedUpdate {}

    /** Append a tool-call block ({@code 🔧 Tool: X} + {@code Query: Y}) to the status buffer. */
    record AppendToolCall(String toolName, String args) implements RenderedUpdate {}

    /** Append a tool-result marker to the status buffer. */
    record AppendObservation(ObservationKind kind, String errorSummary) implements RenderedUpdate {}

    /** Append an error marker to the status buffer. */
    record AppendErrorToStatus(String message) implements RenderedUpdate {}

    /**
     * The tentative answer bubble turned out to be reasoning: delete the answer bubble
     * (or on failure — edit it to a graceful fallback), fold {@code foldedProse} into
     * the status transcript as reasoning, and append a regular tool-call block.
     */
    record RollbackAndAppendToolCall(String toolName, String args, String foldedProse) implements RenderedUpdate {}

    /** No visible update required (e.g. METADATA, FINAL_ANSWER, PARTIAL_ANSWER — handled elsewhere). */
    record NoOp() implements RenderedUpdate {}

    /** Observation outcome classes for rendering. */
    enum ObservationKind {
        RESULT,
        EMPTY,
        FAILED
    }
}
