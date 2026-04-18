package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Message;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the paragraph-boundary-free streaming behaviour of
 * {@link TelegramMessageHandlerActions}: the tentative answer bubble opens on the
 * first PARTIAL_ANSWER chunk of an iteration where no tool call has been seen yet,
 * and existing rollback triggers (text-marker scan and TOOL_CALL event) still fire.
 */
@ExtendWith(MockitoExtension.class)
class TelegramMessageHandlerActionsStreamingTest {

    private static final int MAX_ITERATIONS = 5;

    private static final Long CHAT_ID = 12345L;
    private static final int USER_MSG_ID = 100;
    private static final int STATUS_MSG_ID = 555;
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
        // Disable throttling so every event produces a Telegram call we can assert on.
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
    @DisplayName("should promote answer bubble on the first PARTIAL_ANSWER when no tool call has been seen")
    void shouldPromoteAnswerBubbleOnFirstPartialAnswerWhenNoToolCall() {
        MessageHandlerContext ctx = createContextWithMessage("Ask",
                Set.of(ModelCapabilities.WEB));

        when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                .thenReturn(STATUS_MSG_ID);
        when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), isNull(), eq(true)))
                .thenReturn(ANSWER_MSG_ID);

        // Single short PARTIAL_ANSWER without any paragraph boundary — the old code
        // would have waited for "\n\n" before opening the bubble; the new code opens
        // it immediately and relies on rollback triggers if the content later turns
        // out to be pre-tool reasoning.
        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.partialAnswer("Quick single-line reply.", 0),
                AgentStreamEvent.finalAnswer("Quick single-line reply.", 0));
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        // Exactly one answer bubble send (no reply target).
        ArgumentCaptor<String> answerInitCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageSender, times(1)).sendHtmlAndGetId(eq(CHAT_ID), answerInitCaptor.capture(),
                isNull(), eq(true));
        assertThat(answerInitCaptor.getValue()).contains("Quick single-line reply.");

        assertThat(ctx.getAgentRenderMode())
                .isEqualTo(MessageHandlerContext.AgentRenderMode.TENTATIVE_ANSWER);
        assertThat(ctx.getTentativeAnswerMessageId()).isEqualTo(ANSWER_MSG_ID);
        assertThat(ctx.getErrorType()).isNull();
    }

    @Test
    @DisplayName("should rollback the bubble when a tool marker arrives in PARTIAL_ANSWER after promotion")
    void shouldRollbackBubbleWhenToolMarkerArrivesAfterPromotion() {
        MessageHandlerContext ctx = createContextWithMessage("Compare",
                Set.of(ModelCapabilities.WEB));

        when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                .thenReturn(STATUS_MSG_ID);
        when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), isNull(), eq(true)))
                .thenReturn(ANSWER_MSG_ID);
        when(messageSender.deleteMessage(eq(CHAT_ID), eq(ANSWER_MSG_ID))).thenReturn(true);

        // First PARTIAL_ANSWER promotes the bubble (new behaviour); second chunk leaks
        // a tool marker — the bubble must be rolled back (trigger A).
        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.partialAnswer("Checking sources.", 0),
                AgentStreamEvent.partialAnswer(" <tool_call>fetch_url</tool_call>", 0),
                AgentStreamEvent.finalAnswer("Real answer.", 0));
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        verify(messageSender, times(1)).deleteMessage(eq(CHAT_ID), eq(ANSWER_MSG_ID));
        assertThat(ctx.isTentativeAnswerActive()).isFalse();
        assertThat(ctx.isToolCallSeenThisIteration()).isTrue();
        assertThat(ctx.getTentativeAnswerBuffer().length())
                .as("tentative-answer buffer should be cleared after rollback")
                .isZero();
    }

    @Test
    @DisplayName("should rollback the bubble when a TOOL_CALL event arrives after promotion")
    void shouldRollbackBubbleWhenToolCallEventArrivesAfterPromotion() {
        MessageHandlerContext ctx = createContextWithMessage("Write",
                Set.of(ModelCapabilities.WEB));

        when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                .thenReturn(STATUS_MSG_ID);
        when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), isNull(), eq(true)))
                .thenReturn(ANSWER_MSG_ID);
        when(messageSender.deleteMessage(eq(CHAT_ID), eq(ANSWER_MSG_ID))).thenReturn(true);

        // PARTIAL_ANSWER promotes the bubble on the first chunk (no \n\n needed).
        // The model then decides to call a tool — renderer emits RollbackAndAppendToolCall
        // because tentative-answer is active, which deletes the bubble and appends the
        // tool-call block to the status transcript (trigger B).
        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.partialAnswer("Let me verify first.", 0),
                AgentStreamEvent.toolCall("web_search", "{\"q\":\"facts\"}", 0),
                AgentStreamEvent.observation("found", 0),
                AgentStreamEvent.thinking(1),
                AgentStreamEvent.finalAnswer("Here is the real answer.", 1));
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        verify(messageSender, times(1)).deleteMessage(eq(CHAT_ID), eq(ANSWER_MSG_ID));
        assertThat(ctx.isTentativeAnswerActive()).isFalse();

        ArgumentCaptor<String> statusEditCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageSender, atLeastOnce())
                .editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), statusEditCaptor.capture(), eq(true));
        boolean sawToolCallBlock = statusEditCaptor.getAllValues().stream()
                .anyMatch(html -> html.contains("🔧 <b>Tool:</b>"));
        assertThat(sawToolCallBlock)
                .as("tool-call block must be appended to status after trigger-B rollback")
                .isTrue();
    }

    @Test
    @DisplayName("should not promote the answer bubble when a tool call has already been seen in the iteration")
    void shouldNotPromoteWhenToolCallAlreadySeenInIteration() {
        MessageHandlerContext ctx = createContextWithMessage("Compare",
                Set.of(ModelCapabilities.WEB));

        when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                .thenReturn(STATUS_MSG_ID);

        // TOOL_CALL arrives first — flags the iteration as "tool call seen". Subsequent
        // PARTIAL_ANSWER chunks must NOT open an answer bubble; they only feed the
        // reasoning overlay on the status line.
        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.toolCall("web_search", "{\"q\":\"x\"}", 0),
                AgentStreamEvent.observation("ok", 0),
                AgentStreamEvent.partialAnswer("Some reasoning leaking through.", 0),
                AgentStreamEvent.finalAnswer("Final answer.", 0));
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        // No answer bubble was opened — only the status send (replying to the user
        // message) should have hit sendHtmlAndGetId.
        verify(messageSender, never())
                .sendHtmlAndGetId(eq(CHAT_ID), anyString(), isNull(), anyBoolean());
        assertThat(ctx.isTentativeAnswerActive()).isFalse();
        assertThat(ctx.getTentativeAnswerMessageId()).isNull();
        assertThat(ctx.getAgentRenderMode())
                .isEqualTo(MessageHandlerContext.AgentRenderMode.STATUS_ONLY);
    }

    @Test
    @DisplayName("should append tool-failed marker when observation has error flag")
    void shouldAppendToolFailedMarkerWhenObservationHasError() {
        MessageHandlerContext ctx = createContextWithMessage("Search", Set.of(ModelCapabilities.WEB));

        when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                .thenReturn(STATUS_MSG_ID);

        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.observation("HTTP error 403 Forbidden", true, 0),
                AgentStreamEvent.finalAnswer("Could not retrieve the data.", 0));
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        assertThat(ctx.getStatusBuffer().toString())
                .contains("⚠️ Tool failed:")
                .contains("HTTP error 403 Forbidden");
    }

    @Test
    @DisplayName("should append no-result marker when observation content is blank")
    void shouldAppendNoResultMarkerWhenObservationContentIsBlank() {
        MessageHandlerContext ctx = createContextWithMessage("Search", Set.of(ModelCapabilities.WEB));

        when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                .thenReturn(STATUS_MSG_ID);

        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.observation("", false, 0),
                AgentStreamEvent.finalAnswer("Nothing found.", 0));
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        assertThat(ctx.getStatusBuffer().toString()).contains("📋 No result");
    }

    @Test
    @DisplayName("should append tool-result-received marker when observation is successful")
    void shouldAppendToolResultReceivedWhenObservationSuccess() {
        MessageHandlerContext ctx = createContextWithMessage("Search", Set.of(ModelCapabilities.WEB));

        when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                .thenReturn(STATUS_MSG_ID);

        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.observation("some data", false, 0),
                AgentStreamEvent.finalAnswer("Here is the answer.", 0));
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        assertThat(ctx.getStatusBuffer().toString()).contains("📋 Tool result received");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private MessageHandlerContext createContextWithMessage(String userText,
                                                            Set<ModelCapabilities> capabilities) {
        TelegramCommand command = mock(TelegramCommand.class);
        when(command.userText()).thenReturn(userText);
        when(command.telegramId()).thenReturn(CHAT_ID);

        Message message = mock(Message.class);
        when(message.getMessageId()).thenReturn(USER_MSG_ID);

        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.THREAD_KEY_FIELD, "test-thread-key");
        metadata.put(AICommand.USER_ID_FIELD, "42");

        MessageHandlerContext ctx = new MessageHandlerContext(command, message, s -> {});
        ctx.setMetadata(metadata);
        if (capabilities != null) {
            ctx.setModelCapabilities(capabilities);
        }
        return ctx;
    }
}
