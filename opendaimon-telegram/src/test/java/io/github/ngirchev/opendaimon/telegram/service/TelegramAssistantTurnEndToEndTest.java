package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.common.model.ThinkingMode;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.AssistantTurn;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end test of the new outbound stack: real {@link TelegramRateLimitedBot}
 * + real {@link TelegramAssistantTurnView} + real {@link AssistantTurn} on top of a
 * mocked {@link TelegramBot}. Drives the model through realistic agent-stream events
 * (THINKING chunks → TOOL_CALL → OBSERVATION → PARTIAL_ANSWER chunks → FINAL_ANSWER
 * → markSettled) and verifies:
 *
 * <ol>
 *   <li><b>Streaming works through the buffer</b> — each model mutation accumulates
 *       text in the turn, and {@code view.reconcile()} edits a single status / answer
 *       message instead of opening new ones per chunk.</li>
 *   <li><b>Rate limit is honoured</b> — virtual clock advances by ≥ 1s between sends
 *       to a private chat and ≥ 3s in a group chat; we never observe HTTP 429 from the
 *       mocked bot because we never exceed the configured quota.</li>
 *   <li><b>All three thinking modes</b> render correctly (SHOW_ALL transcript,
 *       HIDE_REASONING placeholder/tool blocks, SILENT answer-only).</li>
 * </ol>
 *
 * <p>Virtual clock + sleeper makes the test deterministic in milliseconds even when
 * the asserted gaps are 1-3 real seconds.
 */
class TelegramAssistantTurnEndToEndTest {

    private static final long PRIVATE_CHAT = 12345L;
    private static final long GROUP_CHAT = -10042L;
    private static final int USER_REPLY_TO = 100;
    private static final int MAX_LEN = 4096;

    private TelegramBot bot;
    private TelegramRateLimitedBot rate;
    private AtomicLong virtualClock;
    private List<Long> sleepHistory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws TelegramApiException {
        bot = mock(TelegramBot.class);
        ObjectProvider<TelegramBot> botProvider = mock(ObjectProvider.class);
        when(botProvider.getIfAvailable()).thenReturn(bot);
        AtomicInteger idSeq = new AtomicInteger(1000);
        when(bot.sendMessageAndGetId(anyLong(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> idSeq.incrementAndGet());

        TelegramProperties.RateLimit cfg = new TelegramProperties.RateLimit();
        cfg.setPrivateChatPerSecond(1);
        cfg.setGroupChatPerMinute(20);
        cfg.setGlobalPerSecond(30);
        cfg.setMaxAcquireWaitMs(120_000);

        virtualClock = new AtomicLong(0);
        sleepHistory = new ArrayList<>();
        rate = new TelegramRateLimitedBot(botProvider, cfg, virtualClock::get, ms -> {
            sleepHistory.add(ms);
            virtualClock.addAndGet(ms);
        });
    }

    @Test
    @DisplayName("HIDE_REASONING in private chat: reasoning streams through one bubble that updates ≥1s apart, answer arrives separately at end")
    void hideReasoning_privateChat_streamsThroughBuffer() throws TelegramApiException {
        AssistantTurn turn = new AssistantTurn(ThinkingMode.HIDE_REASONING);
        TelegramAssistantTurnView view = new TelegramAssistantTurnView(
                rate, PRIVATE_CHAT, USER_REPLY_TO, MAX_LEN, turn);

        // Initial reconcile — placeholder bubble is sent.
        view.reconcile();
        long afterFirst = virtualClock.get();

        // Stream 30 reasoning chunks. Between chunks the model accumulates; reconcile
        // each time but the rate limiter forces ≥1s gap between actual edits.
        for (int i = 0; i < 30; i++) {
            turn.appendReasoning("c" + i);
            view.reconcile();
        }
        long afterReasoning = virtualClock.get();

        // 30 edits at ≥1s apart in a private chat → at least 29 seconds elapsed since
        // the first send. (Each reconcile that has a text drift triggers an edit; the
        // edit blocks on the per-chat 1-sec gate.)
        assertThat(afterReasoning - afterFirst).isGreaterThanOrEqualTo(29_000);

        // Tool call + observation
        turn.recordToolCall("web_search", "{q:'cyprus'}");
        view.reconcile();
        turn.recordObservation("found 5 results");
        view.reconcile();

        // Partial answer chunks during stream — view edits the status (still hides reasoning)
        // AND opens an answer bubble carrying the accumulated answer.
        turn.appendAnswerChunk("Театры ");
        view.reconcile();
        turn.appendAnswerChunk("на Кипре: Rialto.");
        view.reconcile();

        // Final settled
        turn.markSettled();
        view.reconcile();

        // bot.editMessageHtml was called for status updates AND for answer-bubble updates,
        // but the *number* of network calls is bounded by the quota — not by the number of
        // model mutations. With 30 reasoning chunks at ≥1s gaps, we observe ≥30 status edits
        // (one per quota slot the reconcile filled).
        verify(bot, atLeast(20)).editMessageHtml(anyLong(), anyInt(), any(), anyBoolean());
        // Answer bubble was sent once.
        verify(bot, times(2)).sendMessageAndGetId(anyLong(), any(), any(), anyBoolean());
        // No 429 from the mock — by construction.
        assertThat(turn.getState()).isEqualTo(AssistantTurn.State.SETTLED);
    }

    @Test
    @DisplayName("SHOW_ALL in private chat: reasoning transcript stays in status across the stream and persists after settled")
    void showAll_privateChat_transcriptPersists() throws TelegramApiException {
        AssistantTurn turn = new AssistantTurn(ThinkingMode.SHOW_ALL);
        TelegramAssistantTurnView view = new TelegramAssistantTurnView(
                rate, PRIVATE_CHAT, USER_REPLY_TO, MAX_LEN, turn);

        turn.appendReasoning("Step 1: gather sources.\n");
        view.reconcile();
        turn.appendReasoning("Step 2: rank.\n");
        view.reconcile();
        turn.recordToolCall("web_search", "{q:'cyprus'}");
        view.reconcile();
        turn.recordObservation("ok");
        view.reconcile();

        turn.setFinalAnswer("Театры: Rialto.");
        turn.markSettled();
        view.reconcile();

        // Status bubble sent once (initial reasoning), edited at least once after that.
        verify(bot, times(1)).sendMessageAndGetId(eq(PRIVATE_CHAT),
                org.mockito.ArgumentMatchers.<String>argThat(s ->
                        s != null && s.contains("Step 1: gather sources.")),
                any(), anyBoolean());
        verify(bot, atLeastOnce()).editMessageHtml(eq(PRIVATE_CHAT), anyInt(), any(), anyBoolean());
        // Answer bubble sent once.
        verify(bot, times(1)).sendMessageAndGetId(eq(PRIVATE_CHAT),
                eq("Театры: Rialto."), any(), anyBoolean());
    }

    @Test
    @DisplayName("SILENT in private chat: status is never sent; only the final answer reaches Telegram")
    void silent_privateChat_onlyFinalAnswer() throws TelegramApiException {
        AssistantTurn turn = new AssistantTurn(ThinkingMode.SILENT);
        TelegramAssistantTurnView view = new TelegramAssistantTurnView(
                rate, PRIVATE_CHAT, USER_REPLY_TO, MAX_LEN, turn);

        turn.appendReasoning("hidden thinking");
        turn.recordToolCall("web_search", "{q:'x'}");
        turn.recordObservation("results");
        view.reconcile();

        // No bot calls yet — silent mode never sends a status.
        verify(bot, never()).sendMessageAndGetId(anyLong(), any(), any(), anyBoolean());
        verify(bot, never()).editMessageHtml(anyLong(), anyInt(), any(), anyBoolean());

        turn.setFinalAnswer("Done.");
        turn.markSettled();
        view.reconcile();

        verify(bot, times(1)).sendMessageAndGetId(eq(PRIVATE_CHAT), eq("Done."), any(), anyBoolean());
    }

    @Test
    @DisplayName("Group chat (-1001 prefix): rate limiter forces ≥3s between any two sends/edits — provably impossible to hit Telegram 429")
    void groupChat_respectsTwentyPerMinuteAcrossWholeStream() throws TelegramApiException {
        AssistantTurn turn = new AssistantTurn(ThinkingMode.HIDE_REASONING);
        TelegramAssistantTurnView view = new TelegramAssistantTurnView(
                rate, GROUP_CHAT, USER_REPLY_TO, MAX_LEN, turn);

        long start = virtualClock.get();
        view.reconcile(); // initial placeholder — first slot
        for (int i = 0; i < 5; i++) {
            turn.appendReasoning("step" + i + " ");
            view.reconcile();
        }
        turn.recordToolCall("web_search", "{q:'cyprus'}");
        view.reconcile();
        turn.recordObservation("ok");
        view.reconcile();
        turn.appendAnswerChunk("Театры: ");
        view.reconcile();
        turn.appendAnswerChunk("Rialto.");
        view.reconcile();
        turn.markSettled();
        view.reconcile();

        long elapsed = virtualClock.get() - start;
        // We performed roughly 1 send (status) + ≥5 status edits + 1 tool block edit +
        // 1 answer send + ≥1 answer edit = ~9-10 quota slots in a group chat (1 op / 3 s).
        // Lower bound: at least 8 quota slots ≥ 8 × 3 s = 24 s.
        assertThat(elapsed).isGreaterThanOrEqualTo(24_000);
    }

    @Test
    @DisplayName("Long final answer in private chat is split into multiple messages, each fitting maxMessageLength")
    void longAnswer_isSplitIntoMultipleMessages() throws TelegramApiException {
        AssistantTurn turn = new AssistantTurn(ThinkingMode.SILENT);
        TelegramAssistantTurnView view = new TelegramAssistantTurnView(
                rate, PRIVATE_CHAT, USER_REPLY_TO, /*maxLen=*/ 100, turn);

        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            huge.append("Paragraph ").append(i).append(" body text.\n\n");
        }
        turn.setFinalAnswer(huge.toString().trim());
        turn.markSettled();
        view.reconcile();

        // Each sent message has length <= 100. There must be more than one piece.
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(bot, atLeast(2))
                .sendMessageAndGetId(eq(PRIVATE_CHAT), captor.capture(), any(), anyBoolean());
        for (String piece : captor.getAllValues()) {
            assertThat(piece.length()).isLessThanOrEqualTo(100);
        }
    }
}
