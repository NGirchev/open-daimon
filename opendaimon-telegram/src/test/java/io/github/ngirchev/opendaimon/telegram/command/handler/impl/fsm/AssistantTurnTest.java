package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

import io.github.ngirchev.opendaimon.common.model.ThinkingMode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantTurnTest {

    @Test
    void shouldAccumulateReasoningChunks() {
        AssistantTurn turn = new AssistantTurn(ThinkingMode.SHOW_ALL);
        turn.appendReasoning("Hello ");
        turn.appendReasoning("world");
        assertThat(turn.getReasoning()).isEqualTo("Hello world");
    }

    @Test
    void shouldRecordToolCallsInOrderWithObservation() {
        AssistantTurn turn = new AssistantTurn(ThinkingMode.SHOW_ALL);
        turn.recordToolCall("web_search", "{q:'foo'}");
        turn.recordObservation("got 5 results");
        turn.recordToolCall("fetch_url", "{url:'a'}");
        turn.recordObservation("page text");

        assertThat(turn.getToolCalls()).hasSize(2);
        assertThat(turn.getToolCalls().get(0).tool()).isEqualTo("web_search");
        assertThat(turn.getToolCalls().get(0).observation()).isEqualTo("got 5 results");
        assertThat(turn.getToolCalls().get(1).tool()).isEqualTo("fetch_url");
        assertThat(turn.getToolCalls().get(1).observation()).isEqualTo("page text");
    }

    @Test
    void shouldFireOnChangeOnEachMutation() {
        AssistantTurn turn = new AssistantTurn(ThinkingMode.HIDE_REASONING);
        AtomicInteger changes = new AtomicInteger();
        turn.setOnChange(changes::incrementAndGet);

        turn.appendReasoning("a");
        turn.recordToolCall("t", "args");
        turn.recordObservation("obs");
        turn.setFinalAnswer("answer");
        turn.markSettled();

        assertThat(changes.get()).isEqualTo(5);
    }

    @Test
    void shouldIgnoreMutationsAfterSettled() {
        AssistantTurn turn = new AssistantTurn(ThinkingMode.HIDE_REASONING);
        turn.setFinalAnswer("done");
        turn.markSettled();

        turn.appendReasoning("stale");
        turn.recordToolCall("late", "args");
        turn.setFinalAnswer("override");

        assertThat(turn.getReasoning()).isEmpty();
        assertThat(turn.getToolCalls()).isEmpty();
        assertThat(turn.getFinalAnswer()).isEqualTo("done");
        assertThat(turn.getState()).isEqualTo(AssistantTurn.State.SETTLED);
    }

    @Test
    void shouldTransitionToErrorAndIgnoreSubsequentNonTerminalMutations() {
        AssistantTurn turn = new AssistantTurn(ThinkingMode.SILENT);
        RuntimeException boom = new RuntimeException("boom");

        turn.markError(boom);

        assertThat(turn.getState()).isEqualTo(AssistantTurn.State.ERROR);
        assertThat(turn.getError()).isSameAs(boom);

        turn.appendReasoning("after-error");
        assertThat(turn.getReasoning()).isEmpty();
    }

    @Test
    void shouldDefaultToHideReasoningWhenThinkingModeIsNull() {
        AssistantTurn turn = new AssistantTurn(null);
        assertThat(turn.getThinkingMode()).isEqualTo(ThinkingMode.HIDE_REASONING);
    }

    @Test
    void shouldNotFireOnChangeForRedundantOrIgnoredCalls() {
        AssistantTurn turn = new AssistantTurn(ThinkingMode.SHOW_ALL);
        AtomicInteger changes = new AtomicInteger();
        turn.setOnChange(changes::incrementAndGet);

        turn.appendReasoning("");           // empty — ignored
        turn.appendReasoning(null);         // null — ignored
        turn.recordObservation("orphan");   // no tool call yet — ignored

        assertThat(changes.get()).isZero();
    }
}
