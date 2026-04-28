package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerContext;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.TelegramMessageSender;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for TD-1 (`docs/team/td-1-stream-view-state-isolation.md`).
 *
 * <p>Before TD-1 the singleton {@link TelegramAgentStreamView} held a mutable
 * {@code int statusRenderedOffset} field shared across all chats — concurrent flushes
 * leaked state between contexts. This test pins the post-fix invariant: per-stream
 * render offset lives on each {@link MessageHandlerContext}, so two threads flushing
 * the same View instance with two distinct contexts produce per-context offsets that
 * reflect ONLY their own model state.
 *
 * <p>Setup follows the §7 LOW-row mitigation: {@code CyclicBarrier(2)} rendezvous
 * forces both threads to enter the critical section before either proceeds (stronger
 * contention guarantee than {@code CountDownLatch(1)}); a JUnit {@code @Timeout(5s)}
 * fails loud if the production code regresses to a deadlock instead of hanging the CI.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TelegramAgentStreamViewConcurrencyTest {

    private static final long CHAT_ID_A = 100L;
    private static final long CHAT_ID_B = 200L;
    private static final int STATUS_MSG_ID_A = 5001;
    private static final int STATUS_MSG_ID_B = 5002;
    private static final int ROTATED_NEW_MSG_ID_B = 5003;
    /**
     * Tight cap that triggers rotation for the long context's status HTML but never
     * for the short one. Picked well below Telegram's real 4096 to keep test data tiny.
     */
    private static final int MAX_MESSAGE_LENGTH = 60;

    @Mock private TelegramMessageSender messageSender;
    @Mock private TelegramChatPacer telegramChatPacer;

    private TelegramProperties telegramProperties;
    private TelegramAgentStreamView view;
    private ExecutorService executor;

    /**
     * Covers: REQ-1 (View statelessness).
     * Reflection-based invariant: {@link TelegramAgentStreamView} declares only
     * {@code final} instance fields. Guards against a future contributor re-introducing
     * mutable singleton state — direct §7 MEDIUM-risk mitigation.
     */
    @BeforeAll
    static void shouldDeclareOnlyFinalInstanceFields() {
        boolean allFinal = Arrays.stream(TelegramAgentStreamView.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .allMatch(f -> Modifier.isFinal(f.getModifiers()));
        assertThat(allFinal)
                .as("REQ-1: every instance field on TelegramAgentStreamView must be final "
                        + "— mutable singleton state is exactly the TD-1 anti-pattern")
                .isTrue();
    }

    @BeforeEach
    void setUp() throws InterruptedException {
        telegramProperties = new TelegramProperties();
        telegramProperties.setMaxMessageLength(MAX_MESSAGE_LENGTH);
        telegramProperties.setAgentStreamEditMinIntervalMs(0);

        // Pacing slot is always available so flushStatus reaches the offset-mutating branches.
        lenient().when(telegramChatPacer.tryReserve(anyLong())).thenReturn(true);
        lenient().when(telegramChatPacer.reserve(anyLong(), anyLong())).thenReturn(true);

        // After rotation, flushStatus sends the tail as a fresh message and adopts its id.
        // Only context B should reach this branch; stub for both chats to keep the mock generic.
        lenient().when(messageSender.sendHtmlAndGetId(eq(CHAT_ID_B), anyString(), any(), anyBoolean()))
                .thenReturn(ROTATED_NEW_MSG_ID_B);
        lenient().when(messageSender.sendHtmlAndGetId(eq(CHAT_ID_A), anyString(), any(), anyBoolean()))
                .thenReturn(STATUS_MSG_ID_A);
        lenient().when(messageSender.editHtmlReliable(anyLong(), any(), anyString(), anyBoolean(), anyLong()))
                .thenReturn(true);
        lenient().when(messageSender.sendHtmlReliableAndGetId(eq(CHAT_ID_B), anyString(), any(), anyBoolean(), anyLong()))
                .thenReturn(ROTATED_NEW_MSG_ID_B);

        view = new TelegramAgentStreamView(messageSender, telegramChatPacer, telegramProperties);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /**
     * Covers: REQ-3 (concurrency isolation).
     *
     * <p>Two threads simultaneously call {@code view.flush(ctx, model, true)} — one with a
     * SHORT status HTML (no rotation, expected offset stays 0) and one with a LONG status
     * HTML that exceeds {@link #MAX_MESSAGE_LENGTH} (rotation triggers, expected offset is
     * a strictly positive value reflecting the truncated head). Under the pre-TD-1 code,
     * the singleton field would have ended with a single value (whichever thread wrote
     * last), so the two contexts would necessarily read the same offset. With TD-1
     * applied, each context retains its own — the assertion of distinct offsets is the
     * exact regression guard.
     *
     * <p>The {@code CyclicBarrier(2)} rendezvous maximises contention on the View; the
     * JUnit {@code @Timeout(5s)} fails loud rather than hanging the CI if the production
     * code regresses to a deadlock or infinite loop.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("REQ-3: concurrent flushes with two contexts must keep statusRenderedOffset isolated per context")
    void shouldKeepStatusRenderedOffsetIsolatedAcrossConcurrentFlushes() throws Exception {
        // ── ctx A: short status HTML, status bubble already sent (statusMessageId pre-set).
        //          Edit branch runs but no rotation → offset must remain 0.
        MessageHandlerContext ctxA = newContext(CHAT_ID_A);
        ctxA.setStatusMessageId(STATUS_MSG_ID_A);
        TelegramAgentStreamModel modelA = new TelegramAgentStreamModel(false, false);
        // Constructor seeds "💭 Thinking..." (~16 chars) — well under MAX_MESSAGE_LENGTH=60.
        // Force statusDirty so flushStatus actually does work.
        modelA.apply(AgentStreamEvent.thinking(0));
        int statusLengthA = modelA.statusHtml().length();
        assertThat(statusLengthA)
                .as("test precondition: ctxA status HTML must NOT exceed maxMessageLength")
                .isLessThanOrEqualTo(MAX_MESSAGE_LENGTH);

        // ── ctx B: long status HTML, status bubble already sent. Will trigger rotation
        //          inside flushStatus → setStatusRenderedOffset(fullHtml.length() - tail.length()).
        MessageHandlerContext ctxB = newContext(CHAT_ID_B);
        ctxB.setStatusMessageId(STATUS_MSG_ID_B);
        TelegramAgentStreamModel modelB = new TelegramAgentStreamModel(false, false);
        // Build status HTML well past MAX_MESSAGE_LENGTH=60 so rotation fires.
        // Each tool-call+observation pair appends a multi-line block.
        modelB.apply(AgentStreamEvent.thinking(0));
        modelB.apply(AgentStreamEvent.toolCall("web_search", "{\"q\":\"alpha-bravo-charlie\"}", 0));
        modelB.apply(AgentStreamEvent.observation("first observation payload data", false, 0));
        modelB.apply(AgentStreamEvent.thinking(1));
        modelB.apply(AgentStreamEvent.toolCall("web_search", "{\"q\":\"delta-echo-foxtrot\"}", 1));
        modelB.apply(AgentStreamEvent.observation("second observation payload data", false, 1));
        int statusLengthB = modelB.statusHtml().length();
        assertThat(statusLengthB)
                .as("test precondition: ctxB status HTML MUST exceed maxMessageLength so rotation fires")
                .isGreaterThan(MAX_MESSAGE_LENGTH);

        // ── Concurrent rendezvous: both threads call view.flush at the same instant.
        CyclicBarrier barrier = new CyclicBarrier(2);
        Future<?> futureA = executor.submit(() -> {
            await(barrier);
            view.flush(ctxA, modelA, true);
        });
        Future<?> futureB = executor.submit(() -> {
            await(barrier);
            view.flush(ctxB, modelB, true);
        });

        // Surface any thrown exception from the worker threads.
        futureA.get(4, TimeUnit.SECONDS);
        futureB.get(4, TimeUnit.SECONDS);

        // ── REQ-3 assertions: each context must retain its OWN per-stream offset,
        //     consistent with its OWN model. Under the pre-TD-1 singleton-field code,
        //     both contexts would have read the same value (whichever thread wrote last),
        //     so this pair of assertions could not have held simultaneously.
        assertThat(ctxA.getStatusRenderedOffset())
                .as("ctxA: short HTML did not trigger rotation → offset must stay at default 0")
                .isZero();

        assertThat(ctxB.getStatusRenderedOffset())
                .as("ctxB: long HTML triggered rotation → offset must be the head length "
                        + "(fullHtml.length() - tail.length())")
                .isPositive()
                .isLessThan(statusLengthB);

        assertThat(ctxA.getStatusRenderedOffset())
                .as("REQ-3 isolation: ctxA's offset must NOT have been overwritten by ctxB's "
                        + "rotation — proves the field lives on the per-request context, not the singleton")
                .isNotEqualTo(ctxB.getStatusRenderedOffset());
    }

    private MessageHandlerContext newContext(long chatId) {
        TelegramCommand command = mock(TelegramCommand.class);
        when(command.telegramId()).thenReturn(chatId);
        Message message = mock(Message.class);
        // Use distinct reply-to ids to keep the two contexts visually distinct in failure dumps.
        when(message.getMessageId()).thenReturn((int) chatId);
        return new MessageHandlerContext(command, message, s -> {});
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("rendezvous barrier failed", e);
        }
    }

    /**
     * Self-check: the reflection-based REQ-1 assertion above scans
     * {@link TelegramAgentStreamView}'s declared fields. This test pins the assumption
     * that the class actually declares some instance fields (otherwise the {@code allMatch}
     * predicate vacuously returns {@code true} and the guard becomes a tautology).
     */
    @Test
    @DisplayName("REQ-1 self-check: TelegramAgentStreamView declares >0 instance fields (so allMatch isn't vacuous)")
    void shouldExposeAtLeastOneInstanceFieldForTheReq1Guard() {
        long instanceFields = Arrays.stream(TelegramAgentStreamView.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .count();
        assertThat(instanceFields)
                .as("the REQ-1 reflection guard would be vacuous if the class had no instance fields")
                .isPositive();
    }

}
