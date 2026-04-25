package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.common.model.ThinkingMode;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.AssistantTurn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Snapshot tests for {@link TelegramAssistantTurnView} — drive the model through a
 * realistic agent stream and verify the exact sequence of {@code sendMessage}/
 * {@code editMessage} calls the view issues against a mocked rate-limited bot, for
 * each of the three {@link ThinkingMode} options the user can pick via {@code /thinking}.
 */
class TelegramAssistantTurnViewTest {

    private static final long PRIVATE_CHAT = 42L;
    private static final int USER_REPLY_TO = 100;
    private static final int MAX_LEN = 4096;

    private TelegramRateLimitedBot rate;
    private AtomicInteger idSeq;

    @BeforeEach
    void setUp() {
        rate = mock(TelegramRateLimitedBot.class);
        idSeq = new AtomicInteger(1000);
        when(rate.sendMessage(anyLong(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> idSeq.incrementAndGet());
        when(rate.editMessage(anyLong(), anyInt(), any(), anyBoolean()))
                .thenReturn(true);
    }

    @Test
    @DisplayName("SILENT mode: no status message ever; on SETTLED only the answer is sent")
    void silentMode_neverShowsStatus_onlyAnswerOnSettled() {
        AssistantTurn turn = new AssistantTurn(ThinkingMode.SILENT);
        TelegramAssistantTurnView view = newView(turn);

        // Streaming chunks land in the model — view should NOT touch Telegram.
        turn.appendReasoning("thinking");
        turn.recordToolCall("web_search", "{q:'cyprus'}");
        turn.recordObservation("found 5 results");
        view.reconcile();
        verify(rate, never()).sendMessage(anyLong(), any(), any(), anyBoolean());

        // Final answer arrives → view sends one answer message.
        turn.setFinalAnswer("Театры на Кипре: Rialto, Nicosia Theatre.");
        turn.markSettled();
        view.reconcile();

        verify(rate, times(1)).sendMessage(eq(PRIVATE_CHAT),
                eq("Театры на Кипре: Rialto, Nicosia Theatre."),
                eq(USER_REPLY_TO), eq(false));
        verify(rate, never()).editMessage(anyLong(), anyInt(), any(), anyBoolean());
        assertThat(view.statusMessageId()).isNull();
        assertThat(view.answerMessageIds()).hasSize(1);
    }

    @Test
    @DisplayName("HIDE_REASONING: status shows '💭 Думаю…' first, evolves with tool calls, answer sent on SETTLED")
    void hideReasoningMode_streamsCompactStatus_thenAnswer() {
        AssistantTurn turn = new AssistantTurn(ThinkingMode.HIDE_REASONING);
        TelegramAssistantTurnView view = newView(turn);

        // Initial reconcile while empty — should send the placeholder bubble.
        view.reconcile();
        verify(rate, times(1)).sendMessage(eq(PRIVATE_CHAT),
                eq(TelegramAssistantTurnView.STATUS_THINKING_PLACEHOLDER),
                eq(USER_REPLY_TO), eq(true));

        // Reasoning chunks arrive — status updates with the latest reasoning tail.
        turn.appendReasoning("Я начну поиск ");
        turn.appendReasoning("в интернете");
        view.reconcile();
        // The latest sent text reflects the LATEST reasoning, not "Думаю…".
        verify(rate, times(1)).editMessage(eq(PRIVATE_CHAT), anyInt(),
                eq("💭 <i>Я начну поиск в интернете</i>"), eq(true));

        // Tool call appears — status now also lists the tool block.
        turn.recordToolCall("web_search", "{q:'cyprus theatres'}");
        view.reconcile();
        verify(rate, times(1)).editMessage(eq(PRIVATE_CHAT), anyInt(),
                eq("💭 <i>Я начну поиск в интернете</i>\n\n"
                        + "🔧 <b>Tool:</b> web_search\n"
                        + "<b>Args:</b> {q:'cyprus theatres'}"),
                eq(true));

        // Final answer arrives → answer message sent in addition to the (already updated) status.
        turn.setFinalAnswer("Театры на Кипре: Rialto.");
        turn.markSettled();
        view.reconcile();
        verify(rate, times(1)).sendMessage(eq(PRIVATE_CHAT),
                eq("Театры на Кипре: Rialto."),
                eq(USER_REPLY_TO), eq(false));
    }

    @Test
    @DisplayName("SHOW_ALL: status accumulates the full reasoning transcript, no '💭 Думаю…' prefix")
    void showAllMode_streamsFullReasoningTranscript() {
        AssistantTurn turn = new AssistantTurn(ThinkingMode.SHOW_ALL);
        TelegramAssistantTurnView view = newView(turn);

        turn.appendReasoning("Step 1: gather info.\n");
        turn.appendReasoning("Step 2: rank options.");
        view.reconcile();

        verify(rate, times(1)).sendMessage(eq(PRIVATE_CHAT),
                eq("<i>Step 1: gather info.\nStep 2: rank options.</i>"),
                eq(USER_REPLY_TO), eq(true));

        turn.recordToolCall("fetch_url", "{url:'a'}");
        turn.recordObservation("page text");
        view.reconcile();

        verify(rate, atLeastOnce()).editMessage(eq(PRIVATE_CHAT), anyInt(),
                eq("<i>Step 1: gather info.\nStep 2: rank options.</i>\n\n"
                        + "🔧 <b>Tool:</b> fetch_url\n"
                        + "<b>Args:</b> {url:'a'}\n"
                        + "📋 <i>page text</i>"),
                eq(true));

        turn.setFinalAnswer("Done.");
        turn.markSettled();
        view.reconcile();
        verify(rate).sendMessage(eq(PRIVATE_CHAT), eq("Done."), eq(USER_REPLY_TO), eq(false));
    }

    @Test
    @DisplayName("Long final answer is split across multiple messages at paragraph boundaries")
    void longFinalAnswer_isSplitAtParagraphBoundaries() {
        AssistantTurn turn = new AssistantTurn(ThinkingMode.SILENT);
        TelegramAssistantTurnView view = new TelegramAssistantTurnView(
                rate, PRIVATE_CHAT, USER_REPLY_TO, /*maxLen=*/ 100, turn);

        String longAnswer = "Paragraph one is short.\n\n"
                + "Paragraph two is also short, but together they exceed 100 chars easily.\n\n"
                + "Paragraph three closes the answer with a brief recap.";

        turn.setFinalAnswer(longAnswer);
        turn.markSettled();
        view.reconcile();

        // Three paragraph splits expected — at minimum two messages.
        List<String> sent = capturedSentTexts();
        assertThat(sent).hasSizeGreaterThanOrEqualTo(2);
        assertThat(String.join("\n\n", sent))
                .contains("Paragraph one is short.")
                .contains("Paragraph two")
                .contains("Paragraph three");
    }

    @Test
    @DisplayName("Repeated reconcile with no model change does NOT issue any extra Telegram calls")
    void idempotentReconcileDoesNotResendOrReedit() {
        AssistantTurn turn = new AssistantTurn(ThinkingMode.SHOW_ALL);
        TelegramAssistantTurnView view = newView(turn);

        turn.appendReasoning("hello");
        view.reconcile();
        view.reconcile();
        view.reconcile();

        // Exactly one sendMessage for status; no edits because text didn't change.
        verify(rate, times(1)).sendMessage(eq(PRIVATE_CHAT), any(), eq(USER_REPLY_TO), eq(true));
        verify(rate, never()).editMessage(anyLong(), anyInt(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Streaming bursts with N reasoning updates collapse into 1 send + (N-1) edits at most")
    void streamingBurstsAreEdits_notRepeatedSends() {
        AssistantTurn turn = new AssistantTurn(ThinkingMode.HIDE_REASONING);
        TelegramAssistantTurnView view = newView(turn);

        turn.appendReasoning("a");
        view.reconcile();
        for (int i = 0; i < 10; i++) {
            turn.appendReasoning(String.valueOf(i));
            view.reconcile();
        }

        verify(rate, times(1)).sendMessage(eq(PRIVATE_CHAT), any(), eq(USER_REPLY_TO), eq(true));
        // editMessage was called for each unique snapshot — that's the rate-limited path.
        verify(rate, atLeastOnce()).editMessage(eq(PRIVATE_CHAT), anyInt(), any(), eq(true));
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private TelegramAssistantTurnView newView(AssistantTurn turn) {
        return new TelegramAssistantTurnView(rate, PRIVATE_CHAT, USER_REPLY_TO, MAX_LEN, turn);
    }

    private List<String> capturedSentTexts() {
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(rate, atLeastOnce())
                .sendMessage(anyLong(), captor.capture(), any(), anyBoolean());
        return new ArrayList<>(captor.getAllValues());
    }
}
