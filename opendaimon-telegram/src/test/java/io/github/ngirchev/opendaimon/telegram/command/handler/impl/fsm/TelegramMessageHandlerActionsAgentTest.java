package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentStrategy;
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
import org.junit.jupiter.api.Nested;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramMessageHandlerActionsAgentTest {

    private static final int MAX_ITERATIONS = 5;

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

    @BeforeEach
    void setUp() {
        TelegramProperties telegramProperties = new TelegramProperties();
        telegramProperties.setMaxMessageLength(4096);
        agentStreamRenderer = new TelegramAgentStreamRenderer(new ObjectMapper());

        actions = new TelegramMessageHandlerActions(
                telegramUserService, telegramUserSessionService,
                telegramMessageService, aiGatewayRegistry, messageService,
                aiRequestPipeline, telegramProperties, userModelPreferenceService,
                persistentKeyboardService, replyImageAttachmentService, messageSender,
                agentExecutor, agentStreamRenderer, MAX_ITERATIONS);
    }

    @Test
    @DisplayName("generateResponse delegates to agent stream when agentExecutor is present")
    void generateResponse_agentEnabled_delegatesToAgent() {
        MessageHandlerContext ctx = createContextWithMetadata("Search for Java 21 features");

        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.metadata("gpt-4", 3),
                AgentStreamEvent.finalAnswer("Java 21 introduces virtual threads and pattern matching.", 3));
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        assertThat(ctx.getResponseText()).isPresent();
        assertThat(ctx.getResponseText().get()).isEqualTo("Java 21 introduces virtual threads and pattern matching.");
        assertThat(ctx.getResponseModel()).isEqualTo("gpt-4");
        assertThat(ctx.getErrorType()).isNull();
    }

    @Test
    @DisplayName("generateResponse builds correct AgentRequest from context")
    void generateResponse_agentEnabled_buildsCorrectRequest() {
        MessageHandlerContext ctx = createContextWithMetadata("Summarize this");

        Flux<AgentStreamEvent> stream = Flux.just(AgentStreamEvent.finalAnswer("Summary", 1));
        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        when(agentExecutor.executeStream(captor.capture())).thenReturn(stream);

        actions.generateResponse(ctx);

        AgentRequest request = captor.getValue();
        assertThat(request.task()).isEqualTo("Summarize this");
        assertThat(request.conversationId()).isEqualTo("test-thread-key");
        assertThat(request.maxIterations()).isEqualTo(MAX_ITERATIONS);
        assertThat(request.enabledTools()).isEmpty();
        assertThat(request.metadata()).containsKey(AICommand.THREAD_KEY_FIELD);
    }

    @Test
    @DisplayName("generateResponse sets error when agent stream emits ERROR event")
    void generateResponse_agentFailed_setsError() {
        MessageHandlerContext ctx = createContextWithMetadata("Do something");

        Flux<AgentStreamEvent> stream = Flux.just(AgentStreamEvent.error("Agent failed", 2));
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        assertThat(ctx.getErrorType()).isEqualTo(MessageHandlerErrorType.GENERAL);
    }

    @Test
    @DisplayName("generateResponse returns partial answer on MAX_ITERATIONS")
    void generateResponse_maxIterations_returnsPartialAnswer() {
        MessageHandlerContext ctx = createContextWithMetadata("Complex task");

        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.maxIterations("Partial answer so far...", 10));
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        assertThat(ctx.getResponseText()).isPresent();
        assertThat(ctx.getResponseText().get()).isEqualTo("Partial answer so far...");
        assertThat(ctx.getErrorType()).isNull();
    }

    @Test
    @DisplayName("generateResponse sets GENERAL error when agent throws exception")
    void generateResponse_agentException_setsGeneralError() {
        MessageHandlerContext ctx = createContextWithMetadata("Crash test");

        when(agentExecutor.executeStream(any(AgentRequest.class)))
                .thenThrow(new RuntimeException("Agent crashed"));

        actions.generateResponse(ctx);

        assertThat(ctx.getErrorType()).isEqualTo(MessageHandlerErrorType.GENERAL);
        assertThat(ctx.getException()).isNotNull();
        assertThat(ctx.getException().getMessage()).isEqualTo("Agent crashed");
    }

    @Test
    @DisplayName("generateResponse uses AUTO strategy for user with WEB capability")
    void generateResponse_webCapability_usesAutoStrategy() {
        MessageHandlerContext ctx = createContextWithMetadata("Search something",
                Set.of(ModelCapabilities.CHAT, ModelCapabilities.WEB));

        Flux<AgentStreamEvent> stream = Flux.just(AgentStreamEvent.finalAnswer("Found it", 2));
        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        when(agentExecutor.executeStream(captor.capture())).thenReturn(stream);

        actions.generateResponse(ctx);

        assertThat(captor.getValue().strategy()).isEqualTo(AgentStrategy.AUTO);
    }

    @Test
    @DisplayName("generateResponse uses SIMPLE strategy for user without WEB capability")
    void generateResponse_noWebCapability_usesSimpleStrategy() {
        MessageHandlerContext ctx = createContextWithMetadata("Hello",
                Set.of(ModelCapabilities.CHAT));

        Flux<AgentStreamEvent> stream = Flux.just(AgentStreamEvent.finalAnswer("Hi", 1));
        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        when(agentExecutor.executeStream(captor.capture())).thenReturn(stream);

        actions.generateResponse(ctx);

        assertThat(captor.getValue().strategy()).isEqualTo(AgentStrategy.SIMPLE);
    }

    @Test
    @DisplayName("generateResponse uses SIMPLE strategy when capabilities is null")
    void generateResponse_nullCapabilities_usesSimpleStrategy() {
        MessageHandlerContext ctx = createContextWithMetadata("Hello", null);

        Flux<AgentStreamEvent> stream = Flux.just(AgentStreamEvent.finalAnswer("Hi", 1));
        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        when(agentExecutor.executeStream(captor.capture())).thenReturn(stream);

        actions.generateResponse(ctx);

        assertThat(captor.getValue().strategy()).isEqualTo(AgentStrategy.SIMPLE);
    }

    @Test
    @DisplayName("generateResponse uses AUTO strategy for ADMIN with AUTO capability")
    void generateResponse_autoCapability_usesAutoStrategy() {
        MessageHandlerContext ctx = createContextWithMetadata("Search the web",
                Set.of(ModelCapabilities.AUTO));

        Flux<AgentStreamEvent> stream = Flux.just(AgentStreamEvent.finalAnswer("Result", 3));
        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        when(agentExecutor.executeStream(captor.capture())).thenReturn(stream);

        actions.generateResponse(ctx);

        assertThat(captor.getValue().strategy()).isEqualTo(AgentStrategy.AUTO);
        assertThat(ctx.getResponseText()).hasValue("Result");
    }

    @Test
    @DisplayName("generateResponse uses SIMPLE for REGULAR with only CHAT capability")
    void generateResponse_chatOnlyCapability_usesSimpleStrategy() {
        MessageHandlerContext ctx = createContextWithMetadata("Just chat",
                Set.of(ModelCapabilities.CHAT));

        Flux<AgentStreamEvent> stream = Flux.just(AgentStreamEvent.finalAnswer("Reply", 1));
        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        when(agentExecutor.executeStream(captor.capture())).thenReturn(stream);

        actions.generateResponse(ctx);

        assertThat(captor.getValue().strategy()).isEqualTo(AgentStrategy.SIMPLE);
        assertThat(ctx.getResponseText()).hasValue("Reply");
    }

    // ── Edit-in-place agent status message tests ──────────────────────

    @Nested
    @DisplayName("Agent stream edit-in-place")
    class AgentStreamEditInPlace {

        private static final Long CHAT_ID = 12345L;
        private static final int USER_MSG_ID = 100;
        private static final int STATUS_MSG_ID = 555;

        @Test
        @DisplayName("should send first agent event as new message with link preview disabled")
        void shouldSendFirstAgentEventAsNewMessage() {
            MessageHandlerContext ctx = createContextWithMessage("Search web",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.metadata("gpt-4o", 1),
                    AgentStreamEvent.finalAnswer("Result", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            verify(messageSender).sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true));
            assertThat(ctx.isAlreadySentInStream()).isTrue();
            assertThat(ctx.getAgentProgressMessageId()).isEqualTo(STATUS_MSG_ID);
        }

        @Test
        @DisplayName("should edit status message on subsequent events with link preview disabled")
        void shouldEditStatusMessageOnSubsequentEvents() {
            MessageHandlerContext ctx = createContextWithMessage("Search Bitcoin",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.toolCall("web_search", "bitcoin price", 0),
                    AgentStreamEvent.observation("Current price: $50,000", 0),
                    AgentStreamEvent.metadata("gpt-4o", 1),
                    AgentStreamEvent.finalAnswer("Bitcoin is $50,000.", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            verify(messageSender).sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true));
            verify(messageSender, atLeastOnce()).editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), anyString(), eq(true));
        }

        @Test
        @DisplayName("should accumulate all events in one edited message")
        void shouldAccumulateAllEventsInOneMessage() {
            MessageHandlerContext ctx = createContextWithMessage("Search",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.toolCall("web_search", "query", 0),
                    AgentStreamEvent.observation("Result found", 0),
                    AgentStreamEvent.metadata("gpt-4o", 1),
                    AgentStreamEvent.finalAnswer("Answer", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce()).editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), htmlCaptor.capture(), eq(true));

            String lastEditHtml = htmlCaptor.getValue();
            // Thinking chunk replaced by tool_call — only tool call and observation remain
            assertThat(lastEditHtml).doesNotContain("Thinking");
            assertThat(lastEditHtml).contains("Searching the web");
            assertThat(lastEditHtml).contains("Done");
        }

        @Test
        @DisplayName("should send final answer as separate message via messageSender.sendHtml")
        void shouldSendFinalAnswerAsSeparateMessage() {
            MessageHandlerContext ctx = createContextWithMessage("Search",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.toolCall("web_search", "query", 0),
                    AgentStreamEvent.observation("Found", 0),
                    AgentStreamEvent.metadata("gpt-4o", 1),
                    AgentStreamEvent.finalAnswer("The answer is 42.", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            // Final answer sent via messageSender.sendHtml (separate message, no reply)
            ArgumentCaptor<String> finalCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender).sendHtml(eq(CHAT_ID), finalCaptor.capture(), isNull());
            assertThat(finalCaptor.getValue()).contains("The answer is 42.");

            // Final answer must NOT appear in edit calls
            ArgumentCaptor<String> editCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce()).editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), editCaptor.capture(), eq(true));
            for (String editHtml : editCaptor.getAllValues()) {
                assertThat(editHtml).doesNotContain("The answer is 42.");
            }
        }

        @Test
        @DisplayName("should retry send when first sendHtmlAndGetId returns null")
        void shouldRetrySendWhenFirstSendReturnsNull() {
            MessageHandlerContext ctx = createContextWithMessage("Search",
                    Set.of(ModelCapabilities.WEB));

            // First call returns null (bot unavailable), second returns ID.
            // After first consumeNextReplyToMessageId() returns USER_MSG_ID,
            // subsequent calls return null (consumed).
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(null);
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), isNull(), eq(true)))
                    .thenReturn(STATUS_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.toolCall("web_search", "query", 0),
                    AgentStreamEvent.observation("Found", 0),
                    AgentStreamEvent.metadata("gpt-4o", 1),
                    AgentStreamEvent.finalAnswer("Answer", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            // After second send succeeded, OBSERVATION should be an edit
            verify(messageSender, atLeastOnce()).editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), anyString(), eq(true));
        }

        @Test
        @DisplayName("should not call editHtml when no status message ID captured")
        void shouldNotEditWhenNoMessageId() {
            MessageHandlerContext ctx = createContextWithMessage("Search",
                    Set.of(ModelCapabilities.WEB));

            // Bot always unavailable
            when(messageSender.sendHtmlAndGetId(anyLong(), anyString(), any(), eq(true)))
                    .thenReturn(null);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.toolCall("web_search", "query", 0),
                    AgentStreamEvent.metadata("gpt-4o", 1),
                    AgentStreamEvent.finalAnswer("Answer", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            verify(messageSender, never()).editHtml(anyLong(), anyInt(), anyString(), eq(true));
        }

        @Test
        @DisplayName("should replace thinking chunk with next non-thinking event, keep last thinking")
        void shouldReplaceThinkingWithNextEvent() {
            MessageHandlerContext ctx = createContextWithMessage("Search",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.toolCall("web_search", "query", 0),
                    AgentStreamEvent.observation("Result found", 0),
                    AgentStreamEvent.thinking(1),
                    AgentStreamEvent.metadata("gpt-4o", 1),
                    AgentStreamEvent.finalAnswer("Answer", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce()).editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), htmlCaptor.capture(), eq(true));

            String lastEditHtml = htmlCaptor.getValue();
            // Iteration 0: thinking replaced by tool_call
            assertThat(lastEditHtml).contains("Searching the web");
            assertThat(lastEditHtml).contains("Done");
            // Iteration 1: last thinking stays visible (nothing replaced it)
            assertThat(lastEditHtml).contains("Thinking");
        }

        @Test
        @DisplayName("should replace consecutive thinking events (reasoning replaces placeholder)")
        void shouldReplaceConsecutiveThinkingEvents() {
            MessageHandlerContext ctx = createContextWithMessage("Search",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.thinking("I should search for this", 0),
                    AgentStreamEvent.toolCall("web_search", "query", 0),
                    AgentStreamEvent.observation("Found", 0),
                    AgentStreamEvent.metadata("gpt-4o", 1),
                    AgentStreamEvent.finalAnswer("Answer", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce()).editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), htmlCaptor.capture(), eq(true));

            String lastEditHtml = htmlCaptor.getValue();
            // Both thinking events replaced: placeholder by reasoning, reasoning by tool_call
            assertThat(lastEditHtml).doesNotContain("Thinking");
            assertThat(lastEditHtml).doesNotContain("I should search");
            assertThat(lastEditHtml).contains("Searching the web");
            assertThat(lastEditHtml).contains("Done");
        }

        @Test
        @DisplayName("intermediate events should NOT go through streamingParagraphSender")
        void shouldNotSendIntermediateEventsThroughParagraphSender() {
            MessageHandlerContext ctx = createContextWithMessage("Search Bitcoin",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.toolCall("web_search", "bitcoin price", 0),
                    AgentStreamEvent.observation("Price: $50,000", 0),
                    AgentStreamEvent.metadata("gpt-4o", 1),
                    AgentStreamEvent.finalAnswer("Bitcoin is $50,000.", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            // Final answer goes through messageSender.sendHtml, not paragraph sender
            verify(messageSender).sendHtml(eq(CHAT_ID), anyString(), isNull());
            assertThat(ctx.getResponseModel()).isEqualTo("gpt-4o");
            assertThat(ctx.isAlreadySentInStream()).isTrue();
        }

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

    // ── Helpers ──────────────────────────────────────────────────────────

    private MessageHandlerContext createContextWithMetadata(String userText) {
        return createContextWithMetadata(userText, Set.of(ModelCapabilities.AUTO));
    }

    private MessageHandlerContext createContextWithMetadata(String userText, Set<ModelCapabilities> capabilities) {
        TelegramCommand command = mock(TelegramCommand.class);
        when(command.userText()).thenReturn(userText);
        when(command.telegramId()).thenReturn(42L);

        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.THREAD_KEY_FIELD, "test-thread-key");
        metadata.put(AICommand.USER_ID_FIELD, "42");

        MessageHandlerContext ctx = new MessageHandlerContext(command, null, s -> {});
        ctx.setMetadata(metadata);
        if (capabilities != null) {
            ctx.setModelCapabilities(capabilities);
        }
        return ctx;
    }
}
