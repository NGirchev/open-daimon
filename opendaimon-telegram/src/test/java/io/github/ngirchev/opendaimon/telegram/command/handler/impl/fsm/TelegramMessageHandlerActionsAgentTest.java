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
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    @DisplayName("generateResponse returns partial answer on MAX_ITERATIONS with content")
    void generateResponse_maxIterations_returnsPartialAnswer() {
        MessageHandlerContext ctx = createContextWithMetadata("Complex task");

        // The ReActAgentExecutor now emits a MAX_ITERATIONS marker event followed by a
        // FINAL_ANSWER with the tool-less summary — the last event is the terminal one.
        // For backwards compatibility, extractAgentResult still honours MAX_ITERATIONS
        // content when that's the terminal event (legacy producers / tests).
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

    // ── Two-message orchestration tests ──────────────────────────────
    //
    // The agent run now renders to two separate Telegram messages:
    //   • status — iteration log (💭 Thinking…, 🔧 Tool, 📋 result, ⚠️ error).
    //   • answer — separate bubble opened on first paragraph boundary of
    //     PARTIAL_ANSWER prose, deleted on TOOL_CALL (rollback), force-flushed
    //     on FINAL_ANSWER. When no PARTIAL_ANSWER ever opens the bubble, a
    //     fresh paragraph-batched message carries the FINAL_ANSWER.

    @Nested
    @DisplayName("Two-message orchestration")
    class TwoMessageOrchestration {

        private static final Long CHAT_ID = 12345L;
        private static final int USER_MSG_ID = 100;
        private static final int STATUS_MSG_ID = 555;
        private static final int ANSWER_MSG_ID = 777;

        @Test
        @DisplayName("should open a fresh answer bubble after the first paragraph boundary in PARTIAL_ANSWER")
        void shouldCreateStatusAndAnswerMessagesAfterFirstParagraphBoundary() {
            MessageHandlerContext ctx = createContextWithMessage("Ask",
                    Set.of(ModelCapabilities.WEB));

            // Status bubble on first send (replying to the user message); answer
            // bubble on second send (no reply target — implicit thread continuation).
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), isNull(), eq(true)))
                    .thenReturn(ANSWER_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.partialAnswer("First paragraph.", 1),
                    AgentStreamEvent.partialAnswer("\n\nSecond paragraph.", 1),
                    AgentStreamEvent.finalAnswer("First paragraph.\n\nSecond paragraph.", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            // Status bubble seeded with thinking line as a reply to the user.
            ArgumentCaptor<String> statusInitCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender).sendHtmlAndGetId(eq(CHAT_ID), statusInitCaptor.capture(),
                    eq(USER_MSG_ID), eq(true));
            assertThat(statusInitCaptor.getValue()).contains("💭 Thinking");

            // Answer bubble opened later (no reply target) with full PARTIAL_ANSWER buffer.
            ArgumentCaptor<String> answerInitCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender).sendHtmlAndGetId(eq(CHAT_ID), answerInitCaptor.capture(),
                    isNull(), eq(true));
            assertThat(answerInitCaptor.getValue()).contains("First paragraph.");
            assertThat(answerInitCaptor.getValue()).contains("Second paragraph.");

            // Status transitioned to "Answering…" when bubble opened.
            ArgumentCaptor<String> statusEditCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), statusEditCaptor.capture(), eq(true));
            assertThat(statusEditCaptor.getAllValues())
                    .anyMatch(html -> html.contains("ℹ️ Answering"));

            // Answer bubble received at least one edit (final flush).
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(ANSWER_MSG_ID), anyString(), eq(true));

            assertThat(ctx.getResponseText()).hasValue("First paragraph.\n\nSecond paragraph.");
            assertThat(ctx.getErrorType()).isNull();
        }

        @Test
        @DisplayName("status overlay never contains unbalanced <i> tags when PARTIAL_ANSWER carries \\n\\n")
        void shouldProduceWellFormedItalicOverlayWhenPartialAnswerCrossesParagraphBoundary() {
            // Regression: a PARTIAL_ANSWER chunk that contains "\n\n" used to leak its
            // newlines into the <i>…</i> overlay on the status message. The next
            // replaceTrailingThinkingLineWithEscaped call then split the buffer on
            // that internal "\n\n", dropping the closing </i>. Telegram rejected the
            // malformed HTML, fell back to plain text, and the user saw a literal "<i>".
            MessageHandlerContext ctx = createContextWithMessage("Ask",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), isNull(), eq(true)))
                    .thenReturn(ANSWER_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.partialAnswer("Конечно! Вот небольшая история:\n\n", 0),
                    AgentStreamEvent.partialAnswer("## Заголовок\n\nТекст.", 0),
                    AgentStreamEvent.finalAnswer("Конечно! Вот небольшая история:\n\n## Заголовок\n\nТекст.", 0));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            ArgumentCaptor<String> statusEditCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), statusEditCaptor.capture(), eq(true));
            for (String html : statusEditCaptor.getAllValues()) {
                int opens = countOccurrences(html, "<i>");
                int closes = countOccurrences(html, "</i>");
                assertThat(opens)
                        .as("<i>/</i> tag count must balance in status HTML: <<%s>>", html)
                        .isEqualTo(closes);
                // No <i> should wrap content that itself contains \n\n.
                int idx = 0;
                while ((idx = html.indexOf("<i>", idx)) >= 0) {
                    int end = html.indexOf("</i>", idx);
                    assertThat(end).as("every <i> must have a </i>").isGreaterThan(idx);
                    String inside = html.substring(idx + 3, end);
                    assertThat(inside)
                            .as("overlay content must be a single line")
                            .doesNotContain("\n\n");
                    idx = end + 4;
                }
            }
        }

        private static int countOccurrences(String haystack, String needle) {
            int count = 0;
            int idx = 0;
            while ((idx = haystack.indexOf(needle, idx)) >= 0) {
                count++;
                idx += needle.length();
            }
            return count;
        }

        @Test
        @DisplayName("should delete answer and fold prose into status when tool marker leaks into PARTIAL_ANSWER stream")
        void shouldRollbackWhenEmbeddedToolMarkerAppearsInPartialAnswerStream() {
            // Regression for the Qwen/Ollama pseudo-XML tool-call variant that the
            // upstream StreamingAnswerFilter doesn't recognize — <arg_key>/<arg_value>
            // leaked into the answer bubble as raw text. Per spec §"Final answer
            // transition" step 3 and Russian draft point 9, the Telegram layer must
            // scan streamed text for tool markers and rollback on detection.
            MessageHandlerContext ctx = createContextWithMessage("Compare",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), isNull(), eq(true)))
                    .thenReturn(ANSWER_MSG_ID);
            when(messageSender.deleteMessage(eq(CHAT_ID), eq(ANSWER_MSG_ID))).thenReturn(true);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    // First chunk promotes the tentative answer bubble (paragraph boundary).
                    AgentStreamEvent.partialAnswer("Продолжаю сбор информации...\n\n", 0),
                    // Second chunk leaks the embedded tool marker — the bubble must be rolled back.
                    AgentStreamEvent.partialAnswer("fetch_url\n<arg_key>url</arg_key>\n<arg_value>https://example.com</arg_value>\n</tool_call>", 0),
                    AgentStreamEvent.finalAnswer("The real answer.", 0));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            // The tentative answer bubble must have been deleted.
            verify(messageSender).deleteMessage(eq(CHAT_ID), eq(ANSWER_MSG_ID));

            // Tentative state reset; promotion suppression flag set for the iteration.
            assertThat(ctx.isTentativeAnswerActive()).isFalse();
            assertThat(ctx.isToolCallSeenThisIteration()).isTrue();

            // Status received a folded-prose reasoning overlay after rollback.
            ArgumentCaptor<String> statusEditCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), statusEditCaptor.capture(), eq(true));
            boolean sawFoldedProse = statusEditCaptor.getAllValues().stream()
                    .anyMatch(html -> html.contains("<i>") && html.contains("</i>")
                            && html.contains("Продолжаю"));
            assertThat(sawFoldedProse)
                    .as("folded reasoning overlay must appear in status after marker rollback")
                    .isTrue();
        }

        @Test
        @DisplayName("should suppress promotion when tool marker appears before any paragraph boundary in PARTIAL_ANSWER")
        void shouldSuppressPromotionWhenMarkerAppearsBeforeParagraphBoundary() {
            MessageHandlerContext ctx = createContextWithMessage("Compare",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    // Marker appears before a \n\n — no bubble was ever opened; we just
                    // want to make sure we never promote after that in this iteration.
                    AgentStreamEvent.partialAnswer("Let me think... <tool_call>", 0),
                    AgentStreamEvent.partialAnswer("fetch_url</tool_call>\n\nand more text", 0),
                    AgentStreamEvent.finalAnswer("Real answer.", 0));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            // The tentative bubble must never have been opened — only the status send counts.
            verify(messageSender, never()).sendHtmlAndGetId(eq(CHAT_ID), anyString(),
                    isNull(), eq(true));
            assertThat(ctx.isTentativeAnswerActive()).isFalse();
            assertThat(ctx.isToolCallSeenThisIteration()).isTrue();
        }

        @Test
        @DisplayName("should delete answer and fold prose into status when TOOL_CALL arrives during tentative answer")
        void shouldDeleteAnswerAndFoldIntoStatusWhenToolCallArrivesDuringTentativeAnswer() {
            MessageHandlerContext ctx = createContextWithMessage("Write",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), isNull(), eq(true)))
                    .thenReturn(ANSWER_MSG_ID);
            when(messageSender.deleteMessage(eq(CHAT_ID), eq(ANSWER_MSG_ID)))
                    .thenReturn(true);

            // Paragraph boundary → tentative answer opens. THEN the model turns around
            // and calls a tool — the answer bubble must be deleted, its prose folded
            // into status as reasoning, and a tool-call block appended after it.
            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.partialAnswer("Let me think.\n\nActually, I should check.", 0),
                    AgentStreamEvent.toolCall("web_search", "{\"query\":\"facts\"}", 0),
                    AgentStreamEvent.observation("found", 0),
                    AgentStreamEvent.partialAnswer("Here is the real answer.", 1),
                    AgentStreamEvent.finalAnswer("Here is the real answer.", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            // Delete of the tentative answer bubble MUST fire.
            verify(messageSender).deleteMessage(eq(CHAT_ID), eq(ANSWER_MSG_ID));

            // Tentative state reset — a new answer bubble may be opened later in the
            // next iteration if PARTIAL_ANSWER crosses another boundary.
            assertThat(ctx.isTentativeAnswerActive()).isFalse();

            // Status received the folded-prose overlay AND a tool-call block.
            ArgumentCaptor<String> statusEditCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), statusEditCaptor.capture(), eq(true));
            boolean sawFoldedProse = statusEditCaptor.getAllValues().stream()
                    .anyMatch(html -> html.contains("<i>") && html.contains("check"));
            boolean sawToolCall = statusEditCaptor.getAllValues().stream()
                    .anyMatch(html -> html.contains("🔧 Tool"));
            assertThat(sawFoldedProse).as("folded reasoning overlay present").isTrue();
            assertThat(sawToolCall).as("tool-call block present").isTrue();
        }

        @Test
        @DisplayName("should render RESULT, EMPTY and FAILED observation variants distinctly")
        void shouldRenderThreeObservationVariants() {
            MessageHandlerContext ctx = createContextWithMessage("Ask",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.toolCall("web_search", "{\"q\":\"a\"}", 0),
                    AgentStreamEvent.observation("Found 3 items", 0),
                    AgentStreamEvent.toolCall("web_search", "{\"q\":\"b\"}", 1),
                    AgentStreamEvent.observation("", 1),
                    AgentStreamEvent.toolCall("web_search", "{\"q\":\"c\"}", 2),
                    AgentStreamEvent.observation("Network timeout", true, 2),
                    AgentStreamEvent.finalAnswer("done", 2));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            ArgumentCaptor<String> editCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), editCaptor.capture(), eq(true));
            String lastHtml = editCaptor.getValue();

            // All three markers eventually accumulate in the status buffer.
            assertThat(lastHtml).contains("📋 Tool result received");
            assertThat(lastHtml).contains("📋 No result");
            assertThat(lastHtml).contains("⚠️ Tool failed: Network timeout");
        }

        @Test
        @DisplayName("should replace trailing thinking line with reasoning overlay on THINKING with content")
        void shouldReplaceTrailingThinkingLineWhenReasoningEvent() {
            MessageHandlerContext ctx = createContextWithMessage("Ask",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);

            // Iteration 0 starts with null-content THINKING (marker), then THINKING with
            // reasoning text — the "💭 Thinking..." line is replaced with the <i>…</i>
            // overlay carrying the reasoning.
            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.thinking("Checking prices first.", 0),
                    AgentStreamEvent.finalAnswer("done", 0));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            ArgumentCaptor<String> editCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), editCaptor.capture(), eq(true));
            boolean sawReasoning = editCaptor.getAllValues().stream()
                    .anyMatch(html -> html.contains("<i>") && html.contains("Checking prices first."));
            assertThat(sawReasoning).isTrue();

            // The thinking marker should have been replaced — it must NOT coexist with
            // the overlay in the final buffer.
            String finalHtml = editCaptor.getValue();
            assertThat(finalHtml).doesNotContain("💭 Thinking...\n");
        }

        @Test
        @DisplayName("should append a fresh thinking line when a new iteration rolls over")
        void shouldAppendFreshThinkingOnIterationRollover() {
            MessageHandlerContext ctx = createContextWithMessage("Ask",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);

            // Per spec: iter-0's "💭 Thinking..." is replaced by the tool-call block when
            // TOOL_CALL arrives. The iter-1 rollover appends a fresh thinking line below
            // the (completed) iter-0 block — so the final state carries BOTH the iter-0
            // tool log AND exactly one "💭 Thinking..." (the iter-1 placeholder).
            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.toolCall("web_search", "{\"q\":\"x\"}", 0),
                    AgentStreamEvent.observation("ok", 0),
                    AgentStreamEvent.thinking(1),
                    AgentStreamEvent.finalAnswer("done", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            ArgumentCaptor<String> editCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), editCaptor.capture(), eq(true));
            String finalHtml = editCaptor.getValue();
            assertThat(finalHtml).contains("🔧 Tool");
            assertThat(finalHtml).contains("📋 Tool result received");
            int thinkingLines = finalHtml.split("💭 Thinking").length - 1;
            assertThat(thinkingLines).isEqualTo(1);
        }

        @Test
        @DisplayName("should rotate status message when the buffer exceeds the Telegram length limit")
        void shouldRotateStatusMessageAtParagraphBoundaryAtLengthLimit() {
            // Tight limit forces rotation after a few markers.
            telegramProperties.setMaxMessageLength(120);

            int firstStatusId = 111;
            int secondStatusId = 222;

            MessageHandlerContext ctx = createContextWithMessage("Ask",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(firstStatusId);
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), isNull(), eq(true)))
                    .thenReturn(secondStatusId);

            // Tool-call blocks include "\n\n🔧 Tool: …\nQuery: …" — 4 of them push the
            // buffer past 120 chars; rotator cuts at a "\n\n" boundary, sends the head
            // as the finalized old status, starts a fresh message for the tail.
            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.toolCall("web_search", "{\"q\":\"" + "x".repeat(20) + "\"}", 0),
                    AgentStreamEvent.observation("r", 0),
                    AgentStreamEvent.toolCall("web_search", "{\"q\":\"" + "y".repeat(20) + "\"}", 1),
                    AgentStreamEvent.observation("r", 1),
                    AgentStreamEvent.toolCall("web_search", "{\"q\":\"" + "z".repeat(20) + "\"}", 2),
                    AgentStreamEvent.observation("r", 2),
                    AgentStreamEvent.finalAnswer("done", 2));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            // A fresh status message was started for the overflow tail.
            verify(messageSender, atLeastOnce())
                    .sendHtmlAndGetId(eq(CHAT_ID), anyString(), isNull(), eq(true));
            assertThat(ctx.getStatusMessageId()).isEqualTo(secondStatusId);
        }

        @Test
        @DisplayName("should send fresh answer via sendHtml when FINAL_ANSWER arrives with no PARTIAL_ANSWER")
        void shouldSendFreshAnswerWhenFinalAnswerArrivesWithoutPartialAnswer() {
            MessageHandlerContext ctx = createContextWithMessage("Quick question",
                    Set.of(ModelCapabilities.CHAT));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);

            // No PARTIAL_ANSWER → no tentative bubble ever opens → the terminal answer
            // is sent as a fresh, paragraph-batched message (not an edit).
            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.finalAnswer("Terminal only answer.", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            ArgumentCaptor<String> finalCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce()).sendHtml(eq(CHAT_ID), finalCaptor.capture(), isNull());
            assertThat(finalCaptor.getValue()).contains("Terminal only answer.");
            assertThat(ctx.getResponseText()).hasValue("Terminal only answer.");
        }

        @Test
        @DisplayName("should HTML-escape tool arguments and error messages in the status buffer")
        void shouldEscapeHtmlInToolArgumentsAndErrorMessages() {
            MessageHandlerContext ctx = createContextWithMessage("Ask",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.toolCall("web_search", "{\"query\":\"<script>alert(1)</script>\"}", 0),
                    AgentStreamEvent.error("Failure <b>bold</b> & friends", 0));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            ArgumentCaptor<String> editCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), editCaptor.capture(), eq(true));
            String finalHtml = editCaptor.getValue();
            // Raw HTML must not survive into the buffer.
            assertThat(finalHtml).doesNotContain("<script>");
            assertThat(finalHtml).doesNotContain("<b>bold</b>");
            // Escaped form is present.
            assertThat(finalHtml).contains("&lt;script&gt;");
            assertThat(finalHtml).contains("&lt;b&gt;bold&lt;/b&gt;");
            assertThat(finalHtml).contains("&amp; friends");
        }

        @Test
        @DisplayName("should throttle mid-stream status edits and only flush once at termination")
        void shouldThrottleEditsAt1000ms() {
            // Large window — every mid-stream edit is throttled out; only the forced
            // terminal flush lands on Telegram.
            telegramProperties.setAgentStreamEditMinIntervalMs(60_000);

            MessageHandlerContext ctx = createContextWithMessage("Ask",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.thinking("r1", 0),
                    AgentStreamEvent.thinking("r2", 0),
                    AgentStreamEvent.thinking("r3", 0),
                    AgentStreamEvent.finalAnswer("done", 0));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            // ensureStatusMessage seeds the status; then markStatusEdited → lastStatusEditAtMs
            // is "now", so every subsequent edit is inside the 60s window EXCEPT the forced
            // terminal flush. Tool-call / error blocks also force-flush — none here, so only
            // one terminal edit lands.
            verify(messageSender, atMost(1))
                    .editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), anyString(), eq(true));
        }

        @Test
        @DisplayName("should finalize the tentative answer and append ❌ Error to status when the stream errors")
        void shouldFinalizeTentativeAnswerAndAppendErrorOnStreamError() {
            MessageHandlerContext ctx = createContextWithMessage("Ask",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), isNull(), eq(true)))
                    .thenReturn(ANSWER_MSG_ID);

            // Open a tentative answer bubble, then have the stream error out — without a
            // final edit of the answer bubble, the user would see a partially-written
            // answer that looks final but isn't. The error marker is appended to status.
            Flux<AgentStreamEvent> stream = Flux.concat(
                    Flux.just(
                            AgentStreamEvent.partialAnswer("Beginning.\n\nMiddle.", 0)),
                    Flux.error(new RuntimeException("network down")));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            // Answer bubble received at least one edit (the final flush forced by the
            // stream-error handler).
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(ANSWER_MSG_ID), anyString(), eq(true));

            // Status buffer has the ❌ Error marker.
            ArgumentCaptor<String> statusEditCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), statusEditCaptor.capture(), eq(true));
            String finalStatus = statusEditCaptor.getValue();
            assertThat(finalStatus).contains("❌ Error:");
            assertThat(finalStatus).contains("network down");
        }

        @Test
        @DisplayName("should never edit when sendHtmlAndGetId returns null (bot unavailable)")
        void shouldNotEditWhenStatusSendFails() {
            MessageHandlerContext ctx = createContextWithMessage("Ask",
                    Set.of(ModelCapabilities.WEB));

            when(messageSender.sendHtmlAndGetId(anyLong(), anyString(), any(), eq(true)))
                    .thenReturn(null);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.thinking("r1", 0),
                    AgentStreamEvent.finalAnswer("done", 0));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            assertThat(ctx.getStatusMessageId()).isNull();
            verify(messageSender, never()).editHtml(anyLong(), anyInt(), anyString(), eq(true));
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
