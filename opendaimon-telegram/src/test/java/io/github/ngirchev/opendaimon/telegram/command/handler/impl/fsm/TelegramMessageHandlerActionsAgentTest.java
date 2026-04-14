package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentResult;
import io.github.ngirchev.opendaimon.common.agent.AgentState;
import io.github.ngirchev.opendaimon.common.agent.AgentStrategy;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.pipeline.AIRequestPipeline;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;
import io.github.ngirchev.opendaimon.common.service.OpenDaimonMessageService;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
import io.github.ngirchev.opendaimon.telegram.service.ReplyImageAttachmentService;
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

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    private TelegramMessageHandlerActions actions;

    @BeforeEach
    void setUp() {
        TelegramProperties telegramProperties = new TelegramProperties();
        telegramProperties.setMaxMessageLength(4096);

        actions = new TelegramMessageHandlerActions(
                telegramUserService, telegramUserSessionService,
                telegramMessageService, aiGatewayRegistry, messageService,
                aiRequestPipeline, telegramProperties, userModelPreferenceService,
                persistentKeyboardService, replyImageAttachmentService, messageSender,
                agentExecutor, MAX_ITERATIONS);
    }

    @Test
    @DisplayName("generateResponse delegates to agent when agentExecutor is present")
    void generateResponse_agentEnabled_delegatesToAgent() {
        MessageHandlerContext ctx = createContextWithMetadata("Search for Java 21 features");

        AgentResult result = new AgentResult(
                "Java 21 introduces virtual threads and pattern matching.",
                List.of(), AgentState.COMPLETED, 3, Duration.ofSeconds(5), "gpt-4");
        when(agentExecutor.execute(any(AgentRequest.class))).thenReturn(result);

        actions.generateResponse(ctx);

        assertThat(ctx.getResponseText()).isPresent();
        assertThat(ctx.getResponseText().get()).isEqualTo("Java 21 introduces virtual threads and pattern matching.");
        assertThat(ctx.getErrorType()).isNull();
    }

    @Test
    @DisplayName("generateResponse builds correct AgentRequest from context")
    void generateResponse_agentEnabled_buildsCorrectRequest() {
        MessageHandlerContext ctx = createContextWithMetadata("Summarize this");

        AgentResult result = new AgentResult("Summary", List.of(), AgentState.COMPLETED, 1, Duration.ofSeconds(1), null);
        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        when(agentExecutor.execute(captor.capture())).thenReturn(result);

        actions.generateResponse(ctx);

        AgentRequest request = captor.getValue();
        assertThat(request.task()).isEqualTo("Summarize this");
        assertThat(request.conversationId()).isEqualTo("test-thread-key");
        assertThat(request.maxIterations()).isEqualTo(MAX_ITERATIONS);
        assertThat(request.enabledTools()).isEmpty();
        assertThat(request.metadata()).containsKey(AICommand.THREAD_KEY_FIELD);
    }

    @Test
    @DisplayName("generateResponse sets EMPTY_RESPONSE when agent fails without answer")
    void generateResponse_agentFailed_setsEmptyResponse() {
        MessageHandlerContext ctx = createContextWithMetadata("Do something");

        AgentResult result = new AgentResult(null, List.of(), AgentState.FAILED, 2, Duration.ofSeconds(3), null);
        when(agentExecutor.execute(any(AgentRequest.class))).thenReturn(result);

        actions.generateResponse(ctx);

        assertThat(ctx.hasResponse()).isFalse();
        assertThat(ctx.getErrorType()).isEqualTo(MessageHandlerErrorType.EMPTY_RESPONSE);
    }

    @Test
    @DisplayName("generateResponse returns partial answer on MAX_ITERATIONS")
    void generateResponse_maxIterations_returnsPartialAnswer() {
        MessageHandlerContext ctx = createContextWithMetadata("Complex task");

        AgentResult result = new AgentResult(
                "Partial answer so far...", List.of(), AgentState.MAX_ITERATIONS, 10, Duration.ofSeconds(30), null);
        when(agentExecutor.execute(any(AgentRequest.class))).thenReturn(result);

        actions.generateResponse(ctx);

        assertThat(ctx.getResponseText()).isPresent();
        assertThat(ctx.getResponseText().get()).isEqualTo("Partial answer so far...");
        assertThat(ctx.getErrorType()).isNull();
    }

    @Test
    @DisplayName("generateResponse sets GENERAL error when agent throws exception")
    void generateResponse_agentException_setsGeneralError() {
        MessageHandlerContext ctx = createContextWithMetadata("Crash test");

        when(agentExecutor.execute(any(AgentRequest.class)))
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

        AgentResult result = new AgentResult("Found it", List.of(), AgentState.COMPLETED, 2, Duration.ofSeconds(3), null);
        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        when(agentExecutor.execute(captor.capture())).thenReturn(result);

        actions.generateResponse(ctx);

        assertThat(captor.getValue().strategy()).isEqualTo(AgentStrategy.AUTO);
    }

    @Test
    @DisplayName("generateResponse uses SIMPLE strategy for user without WEB capability")
    void generateResponse_noWebCapability_usesSimpleStrategy() {
        MessageHandlerContext ctx = createContextWithMetadata("Hello",
                Set.of(ModelCapabilities.CHAT));

        AgentResult result = new AgentResult("Hi", List.of(), AgentState.COMPLETED, 1, Duration.ofSeconds(1), null);
        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        when(agentExecutor.execute(captor.capture())).thenReturn(result);

        actions.generateResponse(ctx);

        assertThat(captor.getValue().strategy()).isEqualTo(AgentStrategy.SIMPLE);
    }

    @Test
    @DisplayName("generateResponse uses SIMPLE strategy when capabilities is null")
    void generateResponse_nullCapabilities_usesSimpleStrategy() {
        MessageHandlerContext ctx = createContextWithMetadata("Hello", null);

        AgentResult result = new AgentResult("Hi", List.of(), AgentState.COMPLETED, 1, Duration.ofSeconds(1), null);
        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        when(agentExecutor.execute(captor.capture())).thenReturn(result);

        actions.generateResponse(ctx);

        assertThat(captor.getValue().strategy()).isEqualTo(AgentStrategy.SIMPLE);
    }

    @Test
    @DisplayName("generateResponse uses AUTO strategy for ADMIN with AUTO capability")
    void generateResponse_autoCapability_usesAutoStrategy() {
        MessageHandlerContext ctx = createContextWithMetadata("Search the web",
                Set.of(ModelCapabilities.AUTO));

        AgentResult result = new AgentResult("Result", List.of(), AgentState.COMPLETED, 3, Duration.ofSeconds(5), null);
        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        when(agentExecutor.execute(captor.capture())).thenReturn(result);

        actions.generateResponse(ctx);

        assertThat(captor.getValue().strategy()).isEqualTo(AgentStrategy.AUTO);
        assertThat(ctx.getResponseText()).hasValue("Result");
    }

    @Test
    @DisplayName("generateResponse uses SIMPLE for REGULAR with only CHAT capability")
    void generateResponse_chatOnlyCapability_usesSimpleStrategy() {
        MessageHandlerContext ctx = createContextWithMetadata("Just chat",
                Set.of(ModelCapabilities.CHAT));

        AgentResult result = new AgentResult("Reply", List.of(), AgentState.COMPLETED, 1, Duration.ofSeconds(1), null);
        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        when(agentExecutor.execute(captor.capture())).thenReturn(result);

        actions.generateResponse(ctx);

        assertThat(captor.getValue().strategy()).isEqualTo(AgentStrategy.SIMPLE);
        assertThat(ctx.getResponseText()).hasValue("Reply");
    }

    private MessageHandlerContext createContextWithMetadata(String userText) {
        return createContextWithMetadata(userText, Set.of(ModelCapabilities.AUTO));
    }

    private MessageHandlerContext createContextWithMetadata(String userText, Set<ModelCapabilities> capabilities) {
        TelegramCommand command = mock(TelegramCommand.class);
        when(command.userText()).thenReturn(userText);

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
