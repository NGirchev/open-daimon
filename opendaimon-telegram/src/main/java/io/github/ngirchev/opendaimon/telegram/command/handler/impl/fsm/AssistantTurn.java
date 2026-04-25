package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

import io.github.ngirchev.opendaimon.common.model.ThinkingMode;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single source of truth for one assistant turn (one user request → one response).
 *
 * <p>Holds the live state of the agent loop: streaming reasoning, the sequence of
 * tool calls and their observations, and the final answer once committed. The
 * Telegram view layer (see {@code TelegramAssistantTurnView}) reads this model and
 * renders it into N actual Telegram messages — status bubble + answer bubble[s] —
 * according to the user's {@link ThinkingMode} and Telegram's 4096-char message
 * length limit.
 *
 * <p>Mutability: single-writer (the FSM stream pipeline). The {@link #setOnChange}
 * callback fires on every mutation so the view can schedule a reconcile.
 *
 * <p>Lifecycle:
 * <pre>
 *   STREAMING → SETTLED  (FINAL_ANSWER + markSettled)
 *   STREAMING → ERROR    (markError)
 * </pre>
 * Mutations after a terminal state are silently ignored.
 */
public final class AssistantTurn {

    public enum State { STREAMING, SETTLED, ERROR }

    public record ToolCallEntry(String tool, String args, String observation) {
        public ToolCallEntry withObservation(String obs) {
            return new ToolCallEntry(tool, args, obs);
        }
    }

    @Getter private final ThinkingMode thinkingMode;
    private final StringBuilder reasoning = new StringBuilder();
    private final List<ToolCallEntry> toolCalls = new ArrayList<>();
    private final StringBuilder answer = new StringBuilder();
    @Getter private State state = State.STREAMING;
    @Getter private Throwable error;
    private Runnable onChange = () -> {};

    public AssistantTurn(ThinkingMode thinkingMode) {
        this.thinkingMode = thinkingMode != null ? thinkingMode : ThinkingMode.HIDE_REASONING;
    }

    public String getReasoning() {
        return reasoning.toString();
    }

    public List<ToolCallEntry> getToolCalls() {
        return Collections.unmodifiableList(new ArrayList<>(toolCalls));
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange != null ? onChange : () -> {};
    }

    /** Append a streaming reasoning chunk. No-op after terminal state. */
    public void appendReasoning(String chunk) {
        if (chunk == null || chunk.isEmpty() || state != State.STREAMING) {
            return;
        }
        reasoning.append(chunk);
        onChange.run();
    }

    /** Record a new tool call (observation null until {@link #recordObservation} fires). */
    public void recordToolCall(String tool, String args) {
        if (state != State.STREAMING) {
            return;
        }
        toolCalls.add(new ToolCallEntry(tool, args, null));
        onChange.run();
    }

    /** Attach an observation to the last recorded tool call. */
    public void recordObservation(String observation) {
        if (state != State.STREAMING || toolCalls.isEmpty()) {
            return;
        }
        int last = toolCalls.size() - 1;
        toolCalls.set(last, toolCalls.get(last).withObservation(observation));
        onChange.run();
    }

    public String getFinalAnswer() {
        return answer.length() == 0 ? null : answer.toString();
    }

    /**
     * Append a streaming PARTIAL_ANSWER chunk. The view renders the accumulated answer
     * as one or more Telegram messages — already during the stream, not only after
     * settled — so the user sees text growing in place.
     */
    public void appendAnswerChunk(String chunk) {
        if (chunk == null || chunk.isEmpty() || state != State.STREAMING) {
            return;
        }
        answer.append(chunk);
        onChange.run();
    }

    /** Replace the entire answer text (used when the agent emits one consolidated FINAL_ANSWER). */
    public void setFinalAnswer(String text) {
        if (state != State.STREAMING) {
            return;
        }
        answer.setLength(0);
        if (text != null) {
            answer.append(text);
        }
        onChange.run();
    }

    /** Transition to SETTLED — turn is complete, view should render the final layout. */
    public void markSettled() {
        if (state != State.STREAMING) {
            return;
        }
        this.state = State.SETTLED;
        onChange.run();
    }

    /** Transition to ERROR — view should render an error message. */
    public void markError(Throwable t) {
        if (state == State.SETTLED) {
            return;
        }
        this.state = State.ERROR;
        this.error = t;
        onChange.run();
    }
}
