package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Message;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    @BeforeEach
    void setUp() {
        TelegramProperties telegramProperties = new TelegramProperties();
        telegramProperties.setMaxMessageLength(4096);
        agentStreamRenderer = new TelegramAgentStreamRenderer();

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
    @DisplayName("generateResponse recovers partial answer from mixed MAX_ITERATIONS payload")
    void generateResponse_maxIterationsContainsMixedPayload_recoversPartialAnswer() {
        MessageHandlerContext ctx = createContextWithMetadata("Complex task");

        String payload = """
                Я нашел ключевые различия и могу продолжить.
                web_search
                <arg_key>query</arg_key>
                <arg_value>Spring Boot vs Quarkus benchmark 2026</arg_value>
                </tool_call>
                """;

        Flux<AgentStreamEvent> stream = Flux.just(AgentStreamEvent.maxIterations(payload, 10));
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        assertThat(ctx.getResponseText()).hasValue("Я нашел ключевые различия и могу продолжить.");
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

    @Test
    @DisplayName("generateResponse appends intermediate events into one edited progress message")
    void generateResponse_withToolCalls_sendsIntermediateEvents() {
        MessageHandlerContext ctx = createContextWithMetadata("Search for Bitcoin price",
                Set.of(ModelCapabilities.WEB), s -> {});
        when(messageSender.sendHtmlAndGetId(eq(42L), any(), isNull(), eq(true))).thenReturn(700);

        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.toolCall("web_search", "bitcoin price", 0),
                AgentStreamEvent.observation("Current price: $50,000", 0),
                AgentStreamEvent.metadata("gpt-4o", 1),
                AgentStreamEvent.finalAnswer("Bitcoin is currently $50,000.", 1));
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        ArgumentCaptor<String> firstProgress = ArgumentCaptor.forClass(String.class);
        verify(messageSender).sendHtmlAndGetId(eq(42L), firstProgress.capture(), isNull(), eq(true));
        assertThat(firstProgress.getValue()).contains("Thinking");

        ArgumentCaptor<String> editedProgress = ArgumentCaptor.forClass(String.class);
        verify(messageSender, times(1))
                .editHtml(eq(42L), eq(700), editedProgress.capture(), eq(true));
        assertThat(editedProgress.getValue())
                .doesNotContain("Thinking")
                .contains("web_search")
                .contains("Tool result received")
                .doesNotContain("$50,000");

        verify(messageSender).sendHtml(eq(42L), any(), isNull());
        assertThat(ctx.getResponseText()).hasValue("Bitcoin is currently $50,000.");
        assertThat(ctx.getResponseModel()).isEqualTo("gpt-4o");
        assertThat(ctx.isAlreadySentInStream()).isTrue();
        assertThat(ctx.getAgentProgressMessageId()).isEqualTo(700);
    }

    @Test
    @DisplayName("generateResponse streams FINAL_ANSWER_CHUNK via one editable final message")
    void generateResponse_finalAnswerChunks_editOneFinalMessageWithoutFallbackSend() {
        MessageHandlerContext ctx = createContextWithMetadata(
                "Search for Bitcoin price",
                Set.of(ModelCapabilities.WEB),
                s -> {},
                101
        );
        when(messageSender.sendHtmlAndGetId(eq(42L), any(), eq(101), eq(true)))
                .thenReturn(700) // progress message
                .thenReturn(800); // final answer message

        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.toolCall("web_search", "bitcoin price", 0),
                AgentStreamEvent.observation("Current price: $50,000", 0),
                AgentStreamEvent.finalAnswerChunk("Bitcoin is currently ", 1),
                AgentStreamEvent.finalAnswerChunk("$50,000.", 1),
                AgentStreamEvent.finalAnswer("Bitcoin is currently $50,000.", 1)
        );
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        verify(messageSender, times(2))
                .sendHtmlAndGetId(eq(42L), any(), eq(101), eq(true));
        verify(messageSender, times(1)).editHtml(eq(42L), eq(700), any(), eq(true));

        ArgumentCaptor<String> finalEdits = ArgumentCaptor.forClass(String.class);
        verify(messageSender).editHtml(eq(42L), eq(800), finalEdits.capture(), eq(false));
        assertThat(finalEdits.getValue()).contains("Bitcoin is currently").contains("$50,000.");

        verify(messageSender, never()).sendHtml(eq(42L), contains("Bitcoin is currently"), eq(101));
        assertThat(ctx.getAgentFinalAnswerMessageId()).isEqualTo(800);
        assertThat(ctx.getResponseText()).hasValue("Bitcoin is currently $50,000.");
    }

    @Test
    @DisplayName("generateResponse keeps one-char tail so terminal edit can enable link preview without text hacks")
    void generateResponse_finalAnswerChunk_reservesTailForTerminalPreviewEnable() {
        MessageHandlerContext ctx = createContextWithMetadata(
                "Give one link",
                Set.of(ModelCapabilities.WEB),
                s -> {},
                101
        );
        when(messageSender.sendHtmlAndGetId(eq(42L), any(), eq(101), eq(true)))
                .thenReturn(800);

        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.finalAnswerChunk("ABC", 1),
                AgentStreamEvent.finalAnswer("ABC", 1)
        );
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        ArgumentCaptor<String> sentFinal = ArgumentCaptor.forClass(String.class);
        verify(messageSender).sendHtmlAndGetId(eq(42L), sentFinal.capture(), eq(101), eq(true));
        assertThat(sentFinal.getValue()).contains("AB");
        assertThat(sentFinal.getValue()).doesNotContain("ABC");

        ArgumentCaptor<String> finalEdit = ArgumentCaptor.forClass(String.class);
        verify(messageSender).editHtml(eq(42L), eq(800), finalEdit.capture(), eq(false));
        assertThat(finalEdit.getValue()).contains("ABC");
    }

    @Test
    @DisplayName("generateResponse flushes reserved final answer tail when stream completes without terminal event")
    void generateResponse_finalAnswerChunkWithoutTerminal_flushesPendingTail() {
        MessageHandlerContext ctx = createContextWithMetadata(
                "Give one link",
                Set.of(ModelCapabilities.WEB),
                s -> {},
                101
        );
        when(messageSender.sendHtmlAndGetId(eq(42L), any(), eq(101), eq(true)))
                .thenReturn(800);

        Flux<AgentStreamEvent> stream = Flux.just(AgentStreamEvent.finalAnswerChunk("ABC", 1));
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        ArgumentCaptor<String> sentFinal = ArgumentCaptor.forClass(String.class);
        verify(messageSender).sendHtmlAndGetId(eq(42L), sentFinal.capture(), eq(101), eq(true));
        assertThat(sentFinal.getValue()).contains("AB");
        assertThat(sentFinal.getValue()).doesNotContain("ABC");

        ArgumentCaptor<String> finalEdit = ArgumentCaptor.forClass(String.class);
        verify(messageSender).editHtml(eq(42L), eq(800), finalEdit.capture(), eq(false));
        assertThat(finalEdit.getValue()).contains("ABC");

        verify(messageSender, never()).sendHtml(eq(42L), contains("ABC"), eq(101));
        assertThat(ctx.getResponseText()).hasValue("ABC");
    }

    @Test
    @DisplayName("generateResponse batches tiny FINAL_ANSWER_CHUNK updates and flushes once at terminal")
    void generateResponse_finalAnswerChunks_areBatchedBeforeEdit() {
        MessageHandlerContext ctx = createContextWithMetadata(
                "Write a tale",
                Set.of(ModelCapabilities.WEB),
                s -> {},
                101
        );
        when(messageSender.sendHtmlAndGetId(eq(42L), any(), eq(101), eq(true)))
                .thenReturn(700) // progress message
                .thenReturn(800); // final answer message

        String tail = "abcdefghijklmnopqrstuvwxyz".repeat(10);
        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.toolCall("web_search", "query", 0),
                AgentStreamEvent.observation("Tool result", 0),
                AgentStreamEvent.finalAnswerChunk("Start ", 1),
                AgentStreamEvent.finalAnswerChunk(tail, 1),
                AgentStreamEvent.finalAnswer("Start " + tail, 1)
        );
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        // Only one final edit expected: terminal flush of pending buffered chunks.
        verify(messageSender, times(1)).editHtml(eq(42L), eq(800), any(), eq(false));
        verify(messageSender, never()).sendHtml(eq(42L), contains("Start "), eq(101));
        assertThat(ctx.getResponseText()).hasValue("Start " + tail);
    }

    @Test
    @DisplayName("generateResponse continues final stream in a new message when Telegram limit is reached")
    void generateResponse_finalAnswerChunks_overLimit_rollsOverToNextMessage() {
        MessageHandlerContext ctx = createContextWithMetadata(
                "Write a long tale",
                Set.of(ModelCapabilities.WEB),
                s -> {},
                101
        );
        when(messageSender.sendHtmlAndGetId(eq(42L), any(), any(), anyBoolean()))
                .thenReturn(700) // progress message
                .thenReturn(800) // first final answer message
                .thenReturn(801); // second final answer message after rollover

        String longTail = "a".repeat(5000);
        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.toolCall("web_search", "query", 0),
                AgentStreamEvent.observation("Tool result", 0),
                AgentStreamEvent.finalAnswerChunk(longTail, 1),
                AgentStreamEvent.finalAnswer(longTail, 1)
        );
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        ArgumentCaptor<Integer> replyToCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> previewCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(messageSender, times(3))
                .sendHtmlAndGetId(eq(42L), any(), replyToCaptor.capture(), previewCaptor.capture());
        assertThat(replyToCaptor.getAllValues()).containsExactly(101, 101, 101);
        assertThat(previewCaptor.getAllValues()).containsExactly(true, true, false);

        verify(messageSender, times(1)).editHtml(eq(42L), eq(700), any(), eq(true));
        verify(messageSender, never()).editHtml(eq(42L), eq(800), any(), anyBoolean());
        verify(messageSender, never()).editHtml(eq(42L), eq(801), any(), anyBoolean());
        assertThat(ctx.getAgentFinalAnswerMessageId()).isEqualTo(801);
        assertThat(ctx.getResponseText()).hasValue(longTail);
    }

    @Test
    @DisplayName("generateResponse rolls back tentative final answer when chunk contains tool marker")
    void generateResponse_mixedFinalChunk_rollsBackTentativeAnswer() {
        MessageHandlerContext ctx = createContextWithMetadata(
                "Compare frameworks",
                Set.of(ModelCapabilities.WEB),
                s -> {},
                101
        );
        when(messageSender.sendHtmlAndGetId(eq(42L), any(), eq(101), eq(true)))
                .thenReturn(700) // progress message
                .thenReturn(800); // tentative final answer message

        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.finalAnswerChunk("I have some preliminary data. ", 0),
                AgentStreamEvent.finalAnswerChunk("<tool_call><tool_name>web_search</tool_name></tool_call>", 0),
                AgentStreamEvent.toolCall("web_search", "latest benchmark", 0),
                AgentStreamEvent.observation("No result", 0),
                AgentStreamEvent.finalAnswer("Final answer after tool execution.", 1)
        );
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        verify(messageSender).deleteMessage(42L, 800);
        verify(messageSender).sendHtml(eq(42L), contains("Final answer after tool execution."), eq(101));
    }

    @Test
    @DisplayName("generateResponse rolls back tentative final answer when structured TOOL_CALL arrives")
    void generateResponse_structuredToolCallAfterFinalChunk_rollsBackTentativeAnswer() {
        MessageHandlerContext ctx = createContextWithMetadata(
                "Compare frameworks",
                Set.of(ModelCapabilities.WEB),
                s -> {},
                101
        );
        when(messageSender.sendHtmlAndGetId(eq(42L), any(), eq(101), eq(true)))
                .thenReturn(700) // progress message
                .thenReturn(800); // tentative final answer message

        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.finalAnswerChunk("I will gather benchmark data first. ", 0),
                AgentStreamEvent.toolCall("web_search", "{\"query\":\"quarkus spring boot benchmark\"}", 0),
                AgentStreamEvent.observation("Tool result received", 0),
                AgentStreamEvent.finalAnswer("Final answer after tool execution.", 1)
        );
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        verify(messageSender).deleteMessage(42L, 800);
        verify(messageSender).sendHtml(eq(42L), contains("Final answer after tool execution."), eq(101));
    }

    @Test
    @DisplayName("generateResponse rotates progress message when status exceeds Telegram limit")
    void generateResponse_progressOverflow_rotatesMessageWithReplyTo() {
        MessageHandlerContext ctx = createContextWithMetadata(
                "Long status flow",
                Set.of(ModelCapabilities.WEB),
                s -> {},
                101
        );

        AtomicInteger nextMessageId = new AtomicInteger(700);
        when(messageSender.sendHtmlAndGetId(eq(42L), any(), eq(101), eq(true)))
                .thenAnswer(invocation -> nextMessageId.getAndIncrement());

        List<AgentStreamEvent> events = new ArrayList<>();
        events.add(AgentStreamEvent.thinking(0));
        for (int i = 0; i < 180; i++) {
            events.add(AgentStreamEvent.toolCall(
                    "fetch_url",
                    "{\"url\":\"https://example.com/" + i + "\"}",
                    i
            ));
        }
        events.add(AgentStreamEvent.finalAnswer("Done", 1));
        Flux<AgentStreamEvent> stream = Flux.fromIterable(events);
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        ArgumentCaptor<Integer> replyToCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(messageSender, org.mockito.Mockito.atLeast(2))
                .sendHtmlAndGetId(eq(42L), any(), replyToCaptor.capture(), eq(true));
        assertThat(replyToCaptor.getAllValues()).allMatch(replyTo -> replyTo != null && replyTo == 101);
        assertThat(ctx.getAgentProgressMessageId()).isGreaterThan(700);
    }

    @Test
    @DisplayName("generateResponse removes thinking-only progress when final answer arrives without tools")
    void generateResponse_thinkingOnlyProgress_isRemovedOnFinalAnswer() {
        MessageHandlerContext ctx = createContextWithMetadata("Answer directly",
                Set.of(ModelCapabilities.CHAT), s -> {}, 101);
        when(messageSender.sendHtmlAndGetId(eq(42L), any(), eq(101), eq(true))).thenReturn(700);

        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.thinking("Analyzing request", 0),
                AgentStreamEvent.finalAnswer("Done.", 0));
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        ArgumentCaptor<String> firstProgress = ArgumentCaptor.forClass(String.class);
        verify(messageSender).sendHtmlAndGetId(eq(42L), firstProgress.capture(), eq(101), eq(true));
        assertThat(firstProgress.getValue()).contains("Thinking");

        ArgumentCaptor<String> editedProgress = ArgumentCaptor.forClass(String.class);
        verify(messageSender).editHtml(eq(42L), eq(700), editedProgress.capture(), eq(true));
        assertThat(editedProgress.getValue())
                .contains("Analyzing request")
                .doesNotContain("Thinking...");

        verify(messageSender).deleteMessage(eq(42L), eq(700));
        assertThat(ctx.getAgentProgressMessageId()).isNull();
        assertThat(ctx.getResponseText()).hasValue("Done.");
    }

    @Test
    @DisplayName("generateResponse recovers user text from mixed FINAL_ANSWER payload and keeps success path")
    void generateResponse_finalAnswerContainsMixedToolPayload_recoversUserText() {
        MessageHandlerContext ctx = createContextWithMetadata("Compare Quarkus and Spring Boot",
                Set.of(ModelCapabilities.AUTO), s -> {});

        String payload = """
                Я получил доступ к двум статьям с полезной информацией.
                Теперь у меня есть достаточно данных, чтобы дать полноценный ответ.
                http_get
                <arg_key>url</arg_key>
                <arg_value>https://azeynalli1990.medium.com/quarkus-versus-spring-full-comparison-f294803332d0</arg_value>
                </tool_call>
                """;

        Flux<AgentStreamEvent> stream = Flux.just(AgentStreamEvent.finalAnswer(payload, 1));
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        assertThat(ctx.getResponseText())
                .hasValue("Я получил доступ к двум статьям с полезной информацией.\n" +
                        "Теперь у меня есть достаточно данных, чтобы дать полноценный ответ.");
        assertThat(ctx.getErrorType()).isNull();
        assertThat(ctx.getResponseError()).isEmpty();
        verify(messageSender).sendHtml(eq(42L), any(), isNull());
    }

    @Test
    @DisplayName("generateResponse keeps EMPTY_RESPONSE for pure raw tool payload")
    void generateResponse_finalAnswerContainsPureToolPayload_setsEmptyResponseError() {
        MessageHandlerContext ctx = createContextWithMetadata("Compare Quarkus and Spring Boot",
                Set.of(ModelCapabilities.AUTO), s -> {});

        String payload = """
                http_get
                <arg_key>url</arg_key>
                <arg_value>https://azeynalli1990.medium.com/quarkus-versus-spring-full-comparison-f294803332d0</arg_value>
                </tool_call>
                """;

        Flux<AgentStreamEvent> stream = Flux.just(AgentStreamEvent.finalAnswer(payload, 1));
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        assertThat(ctx.getResponseText()).isEmpty();
        assertThat(ctx.getErrorType()).isEqualTo(MessageHandlerErrorType.EMPTY_RESPONSE);
        assertThat(ctx.getResponseError()).hasValue("raw_tool_payload_in_final_answer");
        verify(messageSender, never()).sendHtml(eq(42L), any(), isNull());
    }

    @Test
    @DisplayName("generateResponse retries progress send with same replyTo when first send fails")
    void generateResponse_whenFirstProgressSendFails_retriesWithSameReplyTo() {
        MessageHandlerContext ctx = createContextWithMetadata("Search for Bitcoin price",
                Set.of(ModelCapabilities.WEB), s -> {}, 101);
        when(messageSender.sendHtmlAndGetId(eq(42L), any(), eq(101), eq(true)))
                .thenReturn(null)
                .thenReturn(700);

        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.thinking(0),
                AgentStreamEvent.toolCall("web_search", "bitcoin price", 0),
                AgentStreamEvent.observation("Current price: $50,000", 0),
                AgentStreamEvent.metadata("gpt-4o", 1),
                AgentStreamEvent.finalAnswer("Bitcoin is currently $50,000.", 1));
        when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

        actions.generateResponse(ctx);

        verify(messageSender, org.mockito.Mockito.times(2))
                .sendHtmlAndGetId(eq(42L), any(), eq(101), eq(true));
        verify(messageSender).editHtml(eq(42L), eq(700), any(), eq(true));
        assertThat(ctx.getAgentProgressMessageId()).isEqualTo(700);
    }

    private MessageHandlerContext createContextWithMetadata(String userText) {
        return createContextWithMetadata(userText, Set.of(ModelCapabilities.AUTO));
    }

    private MessageHandlerContext createContextWithMetadata(String userText, Set<ModelCapabilities> capabilities) {
        return createContextWithMetadata(userText, capabilities, s -> {});
    }

    private MessageHandlerContext createContextWithMetadata(String userText,
                                                            Set<ModelCapabilities> capabilities,
                                                            java.util.function.Consumer<String> sender) {
        return createContextWithMetadata(userText, capabilities, sender, null);
    }

    private MessageHandlerContext createContextWithMetadata(String userText,
                                                            Set<ModelCapabilities> capabilities,
                                                            java.util.function.Consumer<String> sender,
                                                            Integer messageId) {
        TelegramCommand command = mock(TelegramCommand.class);
        when(command.userText()).thenReturn(userText);
        when(command.telegramId()).thenReturn(42L);

        Message message = null;
        if (messageId != null) {
            message = mock(Message.class);
            when(message.getMessageId()).thenReturn(messageId);
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.THREAD_KEY_FIELD, "test-thread-key");
        metadata.put(AICommand.USER_ID_FIELD, "42");

        MessageHandlerContext ctx = new MessageHandlerContext(command, message, sender);
        ctx.setMetadata(metadata);
        if (capabilities != null) {
            ctx.setModelCapabilities(capabilities);
        }
        return ctx;
    }
}
