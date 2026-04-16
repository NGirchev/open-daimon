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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
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
        verify(messageSender, org.mockito.Mockito.times(2))
                .editHtml(eq(42L), eq(700), editedProgress.capture(), eq(true));
        assertThat(editedProgress.getAllValues().get(0))
                .contains("Thinking")
                .contains("web_search");
        assertThat(editedProgress.getAllValues().get(1))
                .contains("Thinking")
                .contains("web_search")
                .contains("$50,000");

        verify(messageSender).sendHtml(eq(42L), any(), isNull());
        assertThat(ctx.getResponseText()).hasValue("Bitcoin is currently $50,000.");
        assertThat(ctx.getResponseModel()).isEqualTo("gpt-4o");
        assertThat(ctx.isAlreadySentInStream()).isTrue();
        assertThat(ctx.getAgentProgressMessageId()).isEqualTo(700);
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
