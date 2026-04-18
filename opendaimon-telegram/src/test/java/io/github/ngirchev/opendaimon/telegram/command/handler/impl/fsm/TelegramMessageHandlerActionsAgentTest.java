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

    private TelegramProperties telegramProperties;

    @BeforeEach
    void setUp() {
        telegramProperties = new TelegramProperties();
        telegramProperties.setMaxMessageLength(4096);
        // Unit tests run the stream synchronously — disable throttling so every
        // event produces a Telegram call and the tests can assert on it directly.
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

    // ── Unified transcript streaming tests ───────────────────────────
    //
    // The agent stream feeds a single, growing Telegram message: the first
    // rendered chunk is sent with sendHtmlAndGetId(), captured message id is
    // then re-used for editHtml() on every subsequent chunk. When the markdown
    // buffer would exceed maxMessageLength it resets and a new message starts.
    // THINKING / METADATA / terminal events contribute no transcript text —
    // terminal events only populate responseText for persistence.

    @Nested
    @DisplayName("Unified transcript streaming")
    class UnifiedTranscriptStreaming {

        private static final Long CHAT_ID = 12345L;
        private static final int USER_MSG_ID = 100;
        private static final int TRANSCRIPT_MSG_ID = 555;

        @Test
        @DisplayName("should send first rendered chunk as new reply-message and capture its id")
        void shouldSendFirstRenderedChunkAsNewMessage() {
            MessageHandlerContext ctx = createContextWithMessage("Search web",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(TRANSCRIPT_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.partialAnswer("Hello", 1),
                    AgentStreamEvent.finalAnswer("Hello", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            verify(messageSender).sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true));
            assertThat(ctx.getAgentStreamMessageId()).isEqualTo(TRANSCRIPT_MSG_ID);
            assertThat(ctx.isAlreadySentInStream()).isTrue();
        }

        @Test
        @DisplayName("should edit same captured message on subsequent chunks")
        void shouldEditSameMessageOnSubsequentChunks() {
            MessageHandlerContext ctx = createContextWithMessage("Search",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(TRANSCRIPT_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.partialAnswer("Hello", 1),
                    AgentStreamEvent.partialAnswer(", world", 1),
                    AgentStreamEvent.finalAnswer("Hello, world", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            verify(messageSender).sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true));
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(TRANSCRIPT_MSG_ID), anyString(), eq(true));
        }

        @Test
        @DisplayName("should skip THINKING events so they never appear in transcript")
        void shouldSkipThinkingEventsInTranscript() {
            MessageHandlerContext ctx = createContextWithMessage("Search",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(TRANSCRIPT_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.thinking("I should search", 0),
                    AgentStreamEvent.partialAnswer("Hi", 1),
                    AgentStreamEvent.finalAnswer("Hi", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            // PARTIAL_ANSWER "Hi" is written via editHtml on the placeholder, not via a new send.
            ArgumentCaptor<String> editCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(TRANSCRIPT_MSG_ID), editCaptor.capture(), eq(true));
            assertThat(editCaptor.getValue()).contains("Hi");
            // THINKING payloads must never leak into the transcript.
            for (String html : editCaptor.getAllValues()) {
                assertThat(html).doesNotContain("I should search");
            }
        }

        @Test
        @DisplayName("should capture METADATA model without adding it to transcript")
        void shouldCaptureMetadataModelWithoutTranscriptAppend() {
            MessageHandlerContext ctx = createContextWithMessage("Search",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(TRANSCRIPT_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.metadata("gpt-4o", 0),
                    AgentStreamEvent.partialAnswer("Reply", 1),
                    AgentStreamEvent.finalAnswer("Reply", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            assertThat(ctx.getResponseModel()).isEqualTo("gpt-4o");

            ArgumentCaptor<String> sentCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender).sendHtmlAndGetId(eq(CHAT_ID), sentCaptor.capture(), eq(USER_MSG_ID), eq(true));
            assertThat(sentCaptor.getValue()).doesNotContain("gpt-4o");
        }

        @Test
        @DisplayName("should render tool call and observation markers into transcript")
        void shouldRenderToolCallAndObservationMarkers() {
            MessageHandlerContext ctx = createContextWithMessage("Search Bitcoin",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(TRANSCRIPT_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.toolCall("web_search", "{\"query\":\"bitcoin price\"}", 0),
                    AgentStreamEvent.observation("Price: $50,000", 0),
                    AgentStreamEvent.partialAnswer("Bitcoin is $50,000.", 1),
                    AgentStreamEvent.finalAnswer("Bitcoin is $50,000.", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            ArgumentCaptor<String> editCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(TRANSCRIPT_MSG_ID), editCaptor.capture(), eq(true));

            String lastEditHtml = editCaptor.getValue();
            assertThat(lastEditHtml).contains("Searching the web");
            assertThat(lastEditHtml).contains("done");
            assertThat(lastEditHtml).contains("Bitcoin is $50,000.");
            // Observation payload is intentionally hidden behind the ✅ marker.
            assertThat(lastEditHtml).doesNotContain("Price: $50,000");
        }

        @Test
        @DisplayName("should populate responseText from FINAL_ANSWER without calling sendHtml when transcript exists")
        void shouldPopulateResponseTextFromFinalAnswerWithoutExtraSend() {
            MessageHandlerContext ctx = createContextWithMessage("Search",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(TRANSCRIPT_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.partialAnswer("The answer is ", 1),
                    AgentStreamEvent.partialAnswer("42.", 1),
                    AgentStreamEvent.finalAnswer("The answer is 42.", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            assertThat(ctx.getResponseText()).hasValue("The answer is 42.");
            assertThat(ctx.getAgentStreamMessageId()).isEqualTo(TRANSCRIPT_MSG_ID);
            // The terminal event content already flowed through PARTIAL_ANSWER edits —
            // we must NOT additionally send a separate final message.
            verify(messageSender, never()).sendHtml(eq(CHAT_ID), anyString(), isNull());
        }

        @Test
        @DisplayName("should fall back to sendHtml when placeholder send fails and stream emits only the terminal answer")
        void shouldFallBackToSendHtmlWhenNoTranscriptCreated() {
            MessageHandlerContext ctx = createContextWithMessage("Just say hi",
                    Set.of(ModelCapabilities.CHAT));

            // Placeholder send fails (bot unavailable) → agentStreamMessageId never set.
            // Stream emits no PARTIAL_ANSWER chunks either → paragraph-batch fallback is used.
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(null);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.finalAnswer("Hi there!", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            assertThat(ctx.getAgentStreamMessageId()).isNull();
            ArgumentCaptor<String> finalCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender).sendHtml(eq(CHAT_ID), finalCaptor.capture(), isNull());
            assertThat(finalCaptor.getValue()).contains("Hi there!");
            assertThat(ctx.isAlreadySentInStream()).isTrue();
        }

        @Test
        @DisplayName("should render ERROR event into transcript and set GENERAL error")
        void shouldRenderErrorEventInTranscriptAndSetGeneralError() {
            MessageHandlerContext ctx = createContextWithMessage("Do something",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(TRANSCRIPT_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.partialAnswer("Starting...", 1),
                    AgentStreamEvent.error("Connection timeout", 2));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            assertThat(ctx.getErrorType()).isEqualTo(MessageHandlerErrorType.GENERAL);

            ArgumentCaptor<String> editCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(TRANSCRIPT_MSG_ID), editCaptor.capture(), eq(true));
            assertThat(editCaptor.getValue()).contains("Error:");
            assertThat(editCaptor.getValue()).contains("Connection timeout");
        }

        @Test
        @DisplayName("should never call editHtml when every sendHtmlAndGetId returns null")
        void shouldNotCallEditHtmlWhenSendNeverSucceeds() {
            MessageHandlerContext ctx = createContextWithMessage("Search",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(anyLong(), anyString(), any(), eq(true)))
                    .thenReturn(null);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.partialAnswer("A", 1),
                    AgentStreamEvent.partialAnswer("B", 1),
                    AgentStreamEvent.finalAnswer("AB", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            assertThat(ctx.getAgentStreamMessageId()).isNull();
            verify(messageSender, never()).editHtml(anyLong(), anyInt(), anyString(), eq(true));
        }

        @Test
        @DisplayName("should skip mid-stream edits inside throttle window and flush once at termination")
        void shouldSkipMidStreamEditsInsideThrottleWindow() {
            // Large throttle window: all mid-stream chunks after the first must be dropped
            // from the network; the forced flush at stream termination sends a single edit.
            telegramProperties.setAgentStreamEditMinIntervalMs(60_000);

            MessageHandlerContext ctx = createContextWithMessage("Search",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(TRANSCRIPT_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.partialAnswer("one ", 1),
                    AgentStreamEvent.partialAnswer("two ", 1),
                    AgentStreamEvent.partialAnswer("three", 1),
                    AgentStreamEvent.finalAnswer("one two three", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            // Placeholder sent as new message; first real chunk replaces it (first edit
            // bypasses the throttle window because lastEdit was left at 0); subsequent
            // chunks are throttled away; flushAgentStream() fires the final edit with
            // the full buffer. Total edits: first-chunk replace + final flush.
            ArgumentCaptor<String> editCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(TRANSCRIPT_MSG_ID), editCaptor.capture(), eq(true));
            String lastEdit = editCaptor.getValue();
            assertThat(lastEdit).contains("one");
            assertThat(lastEdit).contains("two");
            assertThat(lastEdit).contains("three");
        }

        @Test
        @DisplayName("should collapse consecutive newlines so stacked markers don't produce blank line runs")
        void shouldCollapseConsecutiveNewlinesInRenderedHtml() {
            MessageHandlerContext ctx = createContextWithMessage("Search",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(TRANSCRIPT_MSG_ID);

            // TOOL_CALL emits "\n\n**🔧 …**\n\n", OBSERVATION emits "\n\n*✅ done*\n\n" —
            // naive concatenation yields "...\n\n\n\n..." which renders as multiple blank
            // lines in Telegram HTML. The normalizer must collapse those to a single "\n\n".
            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.toolCall("web_search", "{\"query\":\"x\"}", 0),
                    AgentStreamEvent.observation("anything", 0),
                    AgentStreamEvent.partialAnswer("text", 1),
                    AgentStreamEvent.finalAnswer("text", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender).sendHtmlAndGetId(eq(CHAT_ID), htmlCaptor.capture(), eq(USER_MSG_ID), eq(true));
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(TRANSCRIPT_MSG_ID), htmlCaptor.capture(), eq(true));

            for (String html : htmlCaptor.getAllValues()) {
                // Telegram HTML parse mode forwards \n verbatim; 3+ consecutive newlines
                // create visible blank-line runs. Normalization caps them at \n\n.
                assertThat(html).doesNotContain("\n\n\n");
                // And no leading blank line at the top of the message.
                assertThat(html).doesNotStartWith("\n");
            }
        }

        @Test
        @DisplayName("should force-flush current buffer to existing message before overflow-reset")
        void shouldForceFlushBeforeOverflowToAvoidLosingThrottledTail() {
            // Tight message limit + very long throttle: mid-stream edits are skipped,
            // the buffer grows, and eventually a chunk triggers overflow. Without the
            // pre-overflow flush, the old message would stay at its last-actually-edited
            // state (usually many chars behind) and the buffered tail would be dropped.
            telegramProperties.setMaxMessageLength(100);
            telegramProperties.setAgentStreamEditMinIntervalMs(60_000);

            int firstMessageId = 555;
            int secondMessageId = 777;

            MessageHandlerContext ctx = createContextWithMessage("task",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(firstMessageId);
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), isNull(), eq(true)))
                    .thenReturn(secondMessageId);

            // After first send (30 chars), next two chunks are throttled and only grow
            // the buffer (to 80). The fourth chunk (50 chars) would push projected to
            // 130 > 100 → overflow. Pre-flush must land the 80-char buffer on the OLD
            // message before the buffer resets to just the 4th chunk.
            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.partialAnswer("a".repeat(30), 1),
                    AgentStreamEvent.partialAnswer("b".repeat(30), 1),
                    AgentStreamEvent.partialAnswer("c".repeat(20), 1),
                    AgentStreamEvent.partialAnswer("d".repeat(50), 1),
                    AgentStreamEvent.finalAnswer("done", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            // Pre-overflow flush must edit the OLD message with the full pre-reset buffer.
            ArgumentCaptor<String> preFlushCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(firstMessageId), preFlushCaptor.capture(), eq(true));
            String lastFlushHtml = preFlushCaptor.getValue();
            assertThat(lastFlushHtml).contains("a".repeat(30));
            assertThat(lastFlushHtml).contains("b".repeat(30));
            assertThat(lastFlushHtml).contains("c".repeat(20));
            // The overflowing chunk must NOT leak into the old message.
            assertThat(lastFlushHtml).doesNotContain("d".repeat(10));

            // New message is started for the overflowing chunk.
            verify(messageSender).sendHtmlAndGetId(eq(CHAT_ID), anyString(), isNull(), eq(true));
            assertThat(ctx.getAgentStreamMessageId()).isEqualTo(secondMessageId);
        }

        @Test
        @DisplayName("should render MAX_ITERATIONS marker at end of stream so user sees why it stopped")
        void shouldRenderMaxIterationsMarkerAtEndOfStream() {
            MessageHandlerContext ctx = createContextWithMessage("Complex task",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(TRANSCRIPT_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.partialAnswer("planning next step", 10),
                    AgentStreamEvent.maxIterations("partial answer so far", 10));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            // Final edit must contain the ⚠️ marker so the abrupt end is explained.
            ArgumentCaptor<String> editCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(TRANSCRIPT_MSG_ID), editCaptor.capture(), eq(true));
            String lastHtml = editCaptor.getValue();
            assertThat(lastHtml).contains("⚠️");
            assertThat(lastHtml).contains("reached iteration limit");

            // responseText is set from the event content for persistence.
            assertThat(ctx.getResponseText()).hasValue("partial answer so far");
            assertThat(ctx.getErrorType()).isNull();
        }

        @Test
        @DisplayName("should send 🤔 Thinking placeholder before the first stream event and replace it with the first chunk")
        void shouldSendThinkingPlaceholderBeforeStreamStarts() {
            MessageHandlerContext ctx = createContextWithMessage("Ask something",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(TRANSCRIPT_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.partialAnswer("Real answer", 1),
                    AgentStreamEvent.finalAnswer("Real answer", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            // Exactly one sendHtmlAndGetId call — for the placeholder — before the first real chunk.
            ArgumentCaptor<String> sentCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender).sendHtmlAndGetId(eq(CHAT_ID), sentCaptor.capture(), eq(USER_MSG_ID), eq(true));
            assertThat(sentCaptor.getValue()).contains("Thinking");
            // Placeholder id is seeded so PARTIAL_ANSWER edits (not re-sends) the message.
            assertThat(ctx.getAgentStreamMessageId()).isEqualTo(TRANSCRIPT_MSG_ID);

            // Real content arrives as an editHtml that fully replaces the placeholder text —
            // raw buffer stays empty across the placeholder send, so "Thinking" isn't accumulated.
            ArgumentCaptor<String> editCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(TRANSCRIPT_MSG_ID), editCaptor.capture(), eq(true));
            String lastEdit = editCaptor.getValue();
            assertThat(lastEdit).contains("Real answer");
            assertThat(lastEdit).doesNotContain("Thinking");
        }

        @Test
        @DisplayName("should discard PARTIAL_ANSWER prose from transcript when the same iteration ends with a TOOL_CALL")
        void shouldDiscardReasoningProseWhenIterationEndsWithToolCall() {
            // ReAct models routinely write prose before a tool call (e.g. "Let me search the web…"),
            // which the provider streams via PARTIAL_ANSWER just like a final answer. We can only
            // distinguish reasoning from answer once we see whether the iteration resolves to a
            // TOOL_CALL; if it does, the prose was reasoning and must not leak into the transcript.
            MessageHandlerContext ctx = createContextWithMessage("Write a story",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(TRANSCRIPT_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    // Iteration 0: prose that is REASONING (followed by a tool call).
                    AgentStreamEvent.partialAnswer("Let me search the web for ideas.", 0),
                    AgentStreamEvent.toolCall("web_search", "{\"query\":\"ideas\"}", 0),
                    AgentStreamEvent.observation("results", 0),
                    // Iteration 1: prose that IS the user-facing answer (no tool call follows).
                    AgentStreamEvent.partialAnswer("Here is the story.", 1),
                    AgentStreamEvent.finalAnswer("Here is the story.", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            ArgumentCaptor<String> editCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(TRANSCRIPT_MSG_ID), editCaptor.capture(), eq(true));

            String finalHtml = editCaptor.getValue();
            // Tool-call marker and final answer are committed to the transcript.
            assertThat(finalHtml).contains("Searching the web");
            assertThat(finalHtml).contains("Here is the story.");
            // Reasoning prose is GONE from the final transcript — the italic overlay was replaced
            // with the 🔧 marker when the tool call fired, and the pending buffer was cleared.
            for (String html : editCaptor.getAllValues()) {
                if (html.equals(finalHtml)) {
                    // The LAST edit is what the user sees after the stream completes — it must
                    // not carry the discarded reasoning prose.
                    assertThat(html).doesNotContain("Let me search the web for ideas.");
                }
            }
            assertThat(ctx.getResponseText()).hasValue("Here is the story.");
        }

        @Test
        @DisplayName("should stream PARTIAL_ANSWER prose as italic thinking overlay until committed")
        void shouldStreamPartialAnswerAsItalicOverlayBeforeCommit() {
            // During streaming we don't yet know if this iteration will end with a tool call,
            // so PARTIAL_ANSWER chunks are rendered as an italic "🤔 …" overlay. On final
            // commit (stream terminates with no tool call in the last iteration) the overlay
            // is replaced with the plain committed HTML.
            MessageHandlerContext ctx = createContextWithMessage("Ask",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(TRANSCRIPT_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.partialAnswer("Streaming answer.", 1),
                    AgentStreamEvent.finalAnswer("Streaming answer.", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            ArgumentCaptor<String> editCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(TRANSCRIPT_MSG_ID), editCaptor.capture(), eq(true));

            // At least one intermediate edit showed the italic thinking overlay.
            boolean sawItalicOverlay = editCaptor.getAllValues().stream()
                    .anyMatch(html -> html.contains("🤔") && html.contains("<i>") && html.contains("</i>"));
            assertThat(sawItalicOverlay)
                    .as("during streaming, PARTIAL_ANSWER should be rendered as italic 🤔 <i>…</i> overlay")
                    .isTrue();

            // Final edit has the same text but NOT wrapped in italic — it's a committed answer.
            String finalHtml = editCaptor.getValue();
            assertThat(finalHtml).contains("Streaming answer.");
            assertThat(finalHtml).doesNotContain("🤔");
            assertThat(finalHtml).doesNotContain("<i>");
        }

        @Test
        @DisplayName("should render terminal answer into the placeholder when stream emits only FINAL_ANSWER")
        void shouldRenderTerminalAnswerIntoPlaceholderWhenOnlyFinalAnswerEmitted() {
            MessageHandlerContext ctx = createContextWithMessage("Quick question",
                    Set.of(ModelCapabilities.CHAT));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(TRANSCRIPT_MSG_ID);

            // No PARTIAL_ANSWER — the terminal event carries the only text. The placeholder
            // would otherwise hang in chat forever; the fallback branch must edit it with
            // the final answer so the user sees a real reply.
            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.finalAnswer("Terminal-only answer", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            ArgumentCaptor<String> editCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(TRANSCRIPT_MSG_ID), editCaptor.capture(), eq(true));
            String lastEdit = editCaptor.getValue();
            assertThat(lastEdit).contains("Terminal-only answer");
            assertThat(lastEdit).doesNotContain("Thinking");
            // No separate paragraph-batch fallback send when the placeholder exists.
            verify(messageSender, never()).sendHtml(eq(CHAT_ID), anyString(), isNull());
            assertThat(ctx.getResponseText()).hasValue("Terminal-only answer");
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
