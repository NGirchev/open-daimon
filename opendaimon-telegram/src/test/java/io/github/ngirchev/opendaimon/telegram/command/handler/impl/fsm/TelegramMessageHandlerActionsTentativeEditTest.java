package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.ai.pipeline.AIRequestPipeline;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;
import io.github.ngirchev.opendaimon.common.service.OpenDaimonMessageService;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
import io.github.ngirchev.opendaimon.telegram.service.ReplyImageAttachmentService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramAgentStreamRenderer;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserSessionService;
import io.github.ngirchev.opendaimon.telegram.service.UserModelPreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Focused regression test for the buggy unconditional second {@code editHtml} in
 * {@link TelegramMessageHandlerActions#editTentativeAnswer(MessageHandlerContext, boolean)}.
 *
 * <p>Before the fix Telegram answered with
 * {@code TelegramApiRequestException: [400] Bad Request: text must be non-empty} whenever
 * the tentative-answer buffer was empty or whitespace-only at flush time — typically right
 * after a rotation that left only a newline in the tail. The log line
 * {@code FSM agentStream: tentative answer bubble send failed — staying in STATUS_ONLY}
 * followed.
 *
 * <p>The method is private; the FSM public entry point would require a full agent stream
 * to reach the specific edge case. Reflection is used to hit the method with a pre-seeded
 * context so the test stays deterministic and narrowly scoped to the guard.
 */
@ExtendWith(MockitoExtension.class)
class TelegramMessageHandlerActionsTentativeEditTest {

    private static final int MAX_ITERATIONS = 5;
    private static final Long CHAT_ID = 12345L;
    private static final int USER_MSG_ID = 100;
    private static final int ANSWER_MSG_ID = 777;

    @Mock private TelegramUserService telegramUserService;
    @Mock private TelegramUserSessionService telegramUserSessionService;
    @Mock private TelegramMessageService telegramMessageService;
    @Mock private AIGatewayRegistry aiGatewayRegistry;
    @Mock private OpenDaimonMessageService messageService;
    @Mock private AIRequestPipeline aiRequestPipeline;
    @Mock private UserModelPreferenceService userModelPreferenceService;
    @Mock private PersistentKeyboardService persistentKeyboardService;
    @Mock private ReplyImageAttachmentService replyImageAttachmentService;
    @Mock private TelegramMessageSender messageSender;
    @Mock private AgentExecutor agentExecutor;

    private TelegramAgentStreamRenderer agentStreamRenderer;
    private TelegramMessageHandlerActions actions;
    private TelegramProperties telegramProperties;

    @BeforeEach
    void setUp() {
        telegramProperties = new TelegramProperties();
        telegramProperties.setMaxMessageLength(4096);
        // No debounce — every forceFlush=false path would still flush on first call; here
        // every test invokes forceFlush=true so the debounce is bypassed regardless.
        telegramProperties.setAgentStreamEditMinIntervalMs(0);
        agentStreamRenderer = new TelegramAgentStreamRenderer(new ObjectMapper());

        actions = new TelegramMessageHandlerActions(
                telegramUserService, telegramUserSessionService,
                telegramMessageService, aiGatewayRegistry, messageService,
                aiRequestPipeline, telegramProperties, userModelPreferenceService,
                persistentKeyboardService, replyImageAttachmentService, messageSender,
                agentExecutor, agentStreamRenderer, MAX_ITERATIONS);
    }

    @Test
    @DisplayName("should skip editHtml and leave lastAnswerEditAtMs untouched when tentative buffer is empty")
    void shouldSkipEditHtmlWhenBufferIsEmpty() throws Exception {
        MessageHandlerContext ctx = newContext();
        ctx.setTentativeAnswerMessageId(ANSWER_MSG_ID);
        // Buffer untouched: length == 0 → renderTentativeBuffer produces empty HTML.

        invokeEditTentativeAnswer(ctx, /*forceFlush=*/ true);

        verifyNoInteractions(messageSender);
        assertThat(ctx.getLastAnswerEditAtMs())
                .as("markAnswerEdited must NOT advance the debounce clock when nothing was sent")
                .isZero();
    }

    @Test
    @DisplayName("should skip editHtml when tentative buffer contains only whitespace")
    void shouldSkipEditHtmlWhenBufferIsWhitespaceOnly() throws Exception {
        MessageHandlerContext ctx = newContext();
        ctx.setTentativeAnswerMessageId(ANSWER_MSG_ID);
        ctx.getTentativeAnswerBuffer().append("\n   \n");

        invokeEditTentativeAnswer(ctx, /*forceFlush=*/ true);

        verify(messageSender, never()).editHtml(any(), any(), anyString(), anyBoolean());
        verify(messageSender, never()).sendHtmlAndGetId(any(), anyString(), any(), anyBoolean());
        assertThat(ctx.getLastAnswerEditAtMs()).isZero();
    }

    @Test
    @DisplayName("should skip the tail edit but still send the rotated head when rotation leaves a blank tail")
    void shouldSkipTailEditButSendRotatedHeadWhenTailIsBlank() throws Exception {
        // Tight budget so a short head triggers rotation; tail is whitespace only — the
        // exact pathological shape the bug exposed: rotate → send fresh bubble for a blank
        // tail → Telegram would then reject the unconditional follow-up edit.
        telegramProperties.setMaxMessageLength(6);

        MessageHandlerContext ctx = newContext();
        ctx.setTentativeAnswerMessageId(ANSWER_MSG_ID);
        // "Hello.   " — 9 chars > maxLength=6. Window = "Hello." has no paragraph/sentence
        // boundary and no whitespace, so the rotator falls back to a hard cut at 6. Head =
        // "Hello.", tail = "   " (three spaces) → renderTentativeBuffer returns blank HTML.
        ctx.getTentativeAnswerBuffer().append("Hello.   ");

        when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), isNull(), anyBoolean()))
                .thenReturn(999);

        invokeEditTentativeAnswer(ctx, /*forceFlush=*/ true);

        // Head edit fires exactly once on the original bubble id.
        verify(messageSender, times(1)).editHtml(eq(CHAT_ID), eq(ANSWER_MSG_ID),
                anyString(), anyBoolean());
        // A fresh bubble is opened for the (still-blank) tail; this path predates the bug
        // and is kept as-is — the reported failure was strictly on the follow-up edit.
        verify(messageSender, times(1)).sendHtmlAndGetId(eq(CHAT_ID), anyString(),
                isNull(), anyBoolean());
        // The bug-fix assertion: no second editHtml against the freshly-created bubble id
        // (999) with a blank tail. Before the fix Telegram rejected this with
        // "Bad Request: text must be non-empty".
        verify(messageSender, never()).editHtml(eq(CHAT_ID), eq(999), anyString(), anyBoolean());
        assertThat(ctx.getLastAnswerEditAtMs())
                .as("no tail edit was sent, debounce clock must stay at 0")
                .isZero();
    }

    @Test
    @DisplayName("should editHtml and mark answer edited when buffer has real content")
    void shouldEditHtmlAndMarkAnswerEditedWhenBufferHasContent() throws Exception {
        MessageHandlerContext ctx = newContext();
        ctx.setTentativeAnswerMessageId(ANSWER_MSG_ID);
        ctx.getTentativeAnswerBuffer().append("Partial answer so far.");

        invokeEditTentativeAnswer(ctx, /*forceFlush=*/ true);

        verify(messageSender, times(1)).editHtml(eq(CHAT_ID), eq(ANSWER_MSG_ID),
                anyString(), anyBoolean());
        assertThat(ctx.getLastAnswerEditAtMs())
                .as("real content was sent, debounce clock must advance")
                .isGreaterThan(0L);
    }

    @Test
    @DisplayName("should return immediately when tentativeAnswerMessageId is null")
    void shouldReturnImmediatelyWhenMessageIdIsNull() throws Exception {
        MessageHandlerContext ctx = newContext();
        // tentativeAnswerMessageId intentionally left null.
        ctx.getTentativeAnswerBuffer().append("Some text that would otherwise render.");

        invokeEditTentativeAnswer(ctx, /*forceFlush=*/ true);

        verifyNoInteractions(messageSender);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private MessageHandlerContext newContext() {
        TelegramCommand command = mock(TelegramCommand.class);
        // Both stubs are lenient because the early-return branch (null tentativeAnswerMessageId)
        // bypasses command.telegramId() entirely, making the stub unused in that specific test.
        lenient().when(command.telegramId()).thenReturn(CHAT_ID);
        org.telegram.telegrambots.meta.api.objects.Message message =
                mock(org.telegram.telegrambots.meta.api.objects.Message.class);
        lenient().when(message.getMessageId()).thenReturn(USER_MSG_ID);
        return new MessageHandlerContext(command, message, s -> {});
    }

    /**
     * Invokes the private {@code editTentativeAnswer} directly. Public entry would require
     * driving an agent stream and relying on incidental rotation — too coarse for a guard
     * regression test.
     */
    private void invokeEditTentativeAnswer(MessageHandlerContext ctx, boolean forceFlush) throws Exception {
        Method method = TelegramMessageHandlerActions.class.getDeclaredMethod(
                "editTentativeAnswer", MessageHandlerContext.class, boolean.class);
        method.setAccessible(true);
        method.invoke(actions, ctx, forceFlush);
    }
}
