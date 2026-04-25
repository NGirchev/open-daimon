package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentStrategy;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.command.ChatAICommand;
import io.github.ngirchev.opendaimon.common.ai.command.FixedModelChatAICommand;
import io.github.ngirchev.opendaimon.common.ai.pipeline.AIRequestPipeline;
import io.github.ngirchev.opendaimon.common.service.AIGateway;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;
import io.github.ngirchev.opendaimon.common.service.OpenDaimonMessageService;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
import io.github.ngirchev.opendaimon.telegram.service.ReplyImageAttachmentService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramAgentStreamRenderer;
import io.github.ngirchev.opendaimon.telegram.service.TelegramAgentStreamView;
import io.github.ngirchev.opendaimon.telegram.service.TelegramChatPacer;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserSessionService;
import io.github.ngirchev.opendaimon.telegram.service.ChatSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.lenient;
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
    @Mock private ChatSettingsService chatSettingsService;
    @Mock private PersistentKeyboardService persistentKeyboardService;
    @Mock private ReplyImageAttachmentService replyImageAttachmentService;
    @Mock private TelegramMessageSender messageSender;
    @Mock private AgentExecutor agentExecutor;
    @Mock private TelegramChatPacer telegramChatPacer;

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
        lenient().when(telegramChatPacer.tryReserve(anyLong())).thenReturn(true);
        try {
            lenient().when(telegramChatPacer.reserve(anyLong(), anyLong())).thenReturn(true);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        TelegramAgentStreamView agentStreamView = new TelegramAgentStreamView(
                messageSender, telegramChatPacer, telegramProperties);
        lenient().when(messageSender.sendHtmlReliableAndGetId(eq(12345L), anyString(), any(), anyBoolean(), anyLong()))
                .thenReturn(777);
        lenient().when(messageSender.editHtmlReliable(eq(12345L), any(), anyString(), anyBoolean(), anyLong()))
                .thenReturn(true);

        actions = new TelegramMessageHandlerActions(
                telegramUserService, telegramUserSessionService,
                telegramMessageService, aiGatewayRegistry, messageService,
                aiRequestPipeline, telegramProperties, chatSettingsService,
                persistentKeyboardService, replyImageAttachmentService, messageSender,
                agentExecutor, agentStreamRenderer, agentStreamView, MAX_ITERATIONS, true);
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
    @DisplayName("generateResponse forwards image attachments from TelegramCommand into AgentRequest")
    void shouldPassAttachmentsToAgentRequestWhenCommandHasImage() {
        // Regression guard for the prod bug (2026-04-25 logs, chatId=-5267226692):
        // photo + caption "что тут?" reached DefaultAICommandFactory with attachments=1,
        // routing resolved a vision-capable model, but AgentRequest had no attachments
        // field — the image was dropped before the prompt was built. Without this test
        // the wiring can silently regress next time someone refactors generateResponse.
        TelegramCommand command = mock(TelegramCommand.class);
        when(command.userText()).thenReturn("что тут?");
        when(command.telegramId()).thenReturn(-5267226692L);
        io.github.ngirchev.opendaimon.common.model.Attachment image =
                new io.github.ngirchev.opendaimon.common.model.Attachment(
                        "photo/abc", "image/jpeg", "photo.jpg", 1024L,
                        io.github.ngirchev.opendaimon.common.model.AttachmentType.IMAGE,
                        new byte[]{1, 2, 3});
        when(command.attachments()).thenReturn(List.of(image));

        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.THREAD_KEY_FIELD, "test-thread-key");
        metadata.put(AICommand.USER_ID_FIELD, "42");
        MessageHandlerContext ctx = new MessageHandlerContext(command, null, s -> {});
        ctx.setMetadata(metadata);
        ctx.setModelCapabilities(Set.of(ModelCapabilities.AUTO));

        Flux<AgentStreamEvent> stream = Flux.just(AgentStreamEvent.finalAnswer("Looks like a cat", 1));
        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        when(agentExecutor.executeStream(captor.capture())).thenReturn(stream);

        actions.generateResponse(ctx);

        AgentRequest request = captor.getValue();
        assertThat(request.attachments())
                .as("Image attachments from TelegramCommand must be carried into AgentRequest "
                        + "so SpringAgentLoopActions can attach Media to the first user message")
                .hasSize(1)
                .first()
                .satisfies(a -> {
                    assertThat(a.type()).isEqualTo(
                            io.github.ngirchev.opendaimon.common.model.AttachmentType.IMAGE);
                    assertThat(a.mimeType()).isEqualTo("image/jpeg");
                });
    }

    @Test
    @DisplayName("generateResponse prefers aiCommand processed attachments over raw command attachments")
    void shouldPreferAiCommandAttachmentsOverRawCommandAttachmentsWhenAiCommandIsChatAICommand() {
        // Regression guard for image-only PDFs in agent mode: AIRequestPipeline renders
        // each PDF page into an IMAGE attachment in mutableAttachments, and the result
        // lands in ChatAICommand.attachments() — not in TelegramCommand.attachments(),
        // which still holds the raw PDF bytes. The agent path must read the pipeline-
        // processed list (mirroring SpringAIGateway.java:384), otherwise the rendered
        // pages are lost and toImageMedia() drops the raw PDF as non-IMAGE.
        TelegramCommand command = mock(TelegramCommand.class);
        // Intentionally no command.userText() / telegramId() / attachments() stubs:
        // when ChatAICommand carries the processed payload, the agent path uses
        // aiCommand.userRole() and aiCommand.attachments() exclusively — Mockito's
        // strict mode flags the raw-command stubs as unnecessary if we add them.
        io.github.ngirchev.opendaimon.common.model.Attachment renderedPage =
                new io.github.ngirchev.opendaimon.common.model.Attachment(
                        "doc/scan-page-1", "image/png", "scan-page-1.png", 2048L,
                        io.github.ngirchev.opendaimon.common.model.AttachmentType.IMAGE,
                        new byte[]{9, 9, 9});
        ChatAICommand processedAiCommand = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION),
                0.7, 1024, "system", "опиши документ",
                Map.of(AICommand.THREAD_KEY_FIELD, "test-thread-key"));
        // Build a fresh ChatAICommand carrying the rendered page in attachments
        // (the no-attachments ctor sets it to List.of(); use the canonical 11-arg
        // ctor instead so we can pin a specific image attachment).
        processedAiCommand = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION),
                Set.of(),
                0.7, 1024, null, "system", "опиши документ", false,
                new HashMap<>(Map.of(AICommand.THREAD_KEY_FIELD, "test-thread-key",
                        AICommand.USER_ID_FIELD, "42")),
                new HashMap<>(),
                List.of(renderedPage));

        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.THREAD_KEY_FIELD, "test-thread-key");
        metadata.put(AICommand.USER_ID_FIELD, "42");
        MessageHandlerContext ctx = new MessageHandlerContext(command, null, s -> {});
        ctx.setMetadata(metadata);
        ctx.setAiCommand(processedAiCommand);
        ctx.setModelCapabilities(processedAiCommand.modelCapabilities());

        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.finalAnswer("Документ описан", 1));
        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        when(agentExecutor.executeStream(captor.capture())).thenReturn(stream);

        actions.generateResponse(ctx);

        AgentRequest request = captor.getValue();
        assertThat(request.attachments())
                .as("agent path must use the pipeline-processed image pages, not the raw PDF")
                .hasSize(1)
                .first()
                .satisfies(a -> {
                    assertThat(a.type()).isEqualTo(
                            io.github.ngirchev.opendaimon.common.model.AttachmentType.IMAGE);
                    assertThat(a.mimeType()).isEqualTo("image/png");
                    assertThat(a.filename()).isEqualTo("scan-page-1.png");
                });
    }

    @Test
    @DisplayName("generateResponse prefers FixedModelChatAICommand processed attachments over raw command attachments")
    void shouldPreferAiCommandAttachmentsOverRawCommandAttachmentsWhenAiCommandIsFixedModelChatAICommand() {
        // Regression guard mirroring the ChatAICommand case for the fixed-model branch:
        // when a user pinned a preferred model, DefaultAICommandFactory returns a
        // FixedModelChatAICommand instead of a ChatAICommand. AIRequestPipeline still
        // renders an image-only PDF page-by-page into IMAGE attachments and parks the
        // result on the AI command — but on FixedModelChatAICommand.attachments(), not
        // on TelegramCommand.attachments(). The agent path must inspect this branch
        // (mirroring SpringAIGateway:383-387), otherwise fixed-model agent runs drop
        // the rendered pages and pass the original PDF that toImageMedia() discards.
        TelegramCommand command = mock(TelegramCommand.class);
        // Intentionally no command.userText() / telegramId() / attachments() stubs:
        // when the AI command carries the processed payload, the agent path uses
        // aiCommand.userRole() and aiCommand.attachments() exclusively.
        io.github.ngirchev.opendaimon.common.model.Attachment renderedPage =
                new io.github.ngirchev.opendaimon.common.model.Attachment(
                        "doc/scan-page-1", "image/png", "scan-page-1.png", 2048L,
                        io.github.ngirchev.opendaimon.common.model.AttachmentType.IMAGE,
                        new byte[]{9, 9, 9});
        FixedModelChatAICommand processedAiCommand = new FixedModelChatAICommand(
                "openrouter/google/gemini-2.5-flash-preview",
                Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION),
                0.7, 1024, null, "system", "опиши документ", false,
                new HashMap<>(Map.of(AICommand.THREAD_KEY_FIELD, "test-thread-key",
                        AICommand.USER_ID_FIELD, "42")),
                new HashMap<>(),
                List.of(renderedPage));

        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.THREAD_KEY_FIELD, "test-thread-key");
        metadata.put(AICommand.USER_ID_FIELD, "42");
        MessageHandlerContext ctx = new MessageHandlerContext(command, null, s -> {});
        ctx.setMetadata(metadata);
        ctx.setAiCommand(processedAiCommand);
        ctx.setModelCapabilities(processedAiCommand.modelCapabilities());

        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.finalAnswer("Документ описан", 1));
        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        when(agentExecutor.executeStream(captor.capture())).thenReturn(stream);

        actions.generateResponse(ctx);

        AgentRequest request = captor.getValue();
        assertThat(request.attachments())
                .as("agent path must use the pipeline-processed image pages from "
                        + "FixedModelChatAICommand, not the raw PDF on TelegramCommand")
                .hasSize(1)
                .first()
                .satisfies(a -> {
                    assertThat(a.type()).isEqualTo(
                            io.github.ngirchev.opendaimon.common.model.AttachmentType.IMAGE);
                    assertThat(a.mimeType()).isEqualTo("image/png");
                    assertThat(a.filename()).isEqualTo("scan-page-1.png");
                });
    }

    @Test
    @DisplayName("generateResponse uses aiCommand.userRole (RAG-augmented) as agent task, not raw command.userText")
    void shouldPassAugmentedUserRoleAsAgentTaskWhenChatAICommandHasRagAugmentedQuery() {
        // Regression guard for textual PDF / DOCX in agent mode: AIRequestPipeline runs RAG
        // (extract text → chunk → embedding → similarity search → augment) BEFORE the
        // agent-vs-gateway branching, and parks the augmented query on
        // ChatAICommand.userRole(). The agent path must read userRole() and not the raw
        // TelegramCommand.userText(), otherwise the document content silently disappears
        // before the prompt and the model answers from the bare caption only.
        TelegramCommand command = mock(TelegramCommand.class);
        // No command.userText() / attachments() stubs — the ChatAICommand path must not
        // touch them when userRole is set; Mockito strict mode would flag any unused stub.

        String rawCaption = "сколько было упомянуто в документе компаний?";
        String augmentedQuery = "Context:\nThe report mentions five companies: Acme, Globex, Initech, "
                + "Umbrella and Soylent.\n\nQuestion: " + rawCaption;

        ChatAICommand processedAiCommand = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT),
                Set.of(),
                0.7, 1024, null, "system", augmentedQuery, false,
                new HashMap<>(Map.of(AICommand.THREAD_KEY_FIELD, "test-thread-key",
                        AICommand.USER_ID_FIELD, "42")),
                new HashMap<>(),
                List.of());

        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.THREAD_KEY_FIELD, "test-thread-key");
        metadata.put(AICommand.USER_ID_FIELD, "42");
        MessageHandlerContext ctx = new MessageHandlerContext(command, null, s -> {});
        ctx.setMetadata(metadata);
        ctx.setAiCommand(processedAiCommand);
        ctx.setModelCapabilities(processedAiCommand.modelCapabilities());

        Flux<AgentStreamEvent> stream = Flux.just(
                AgentStreamEvent.finalAnswer("Пять компаний", 1));
        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        when(agentExecutor.executeStream(captor.capture())).thenReturn(stream);

        actions.generateResponse(ctx);

        AgentRequest request = captor.getValue();
        assertThat(request.task())
                .as("agent task must be the pipeline-augmented query carrying RAG context, "
                        + "not the bare caption — otherwise document content is lost before the prompt")
                .isEqualTo(augmentedQuery)
                .contains("five companies")
                .contains(rawCaption);
    }

    @Test
    @DisplayName("generateResponse passes empty attachments when TelegramCommand has none")
    void shouldPassEmptyAttachmentsToAgentRequestWhenCommandHasNoAttachments() {
        // Negative guard — text-only commands must not crash on null attachments() and
        // must produce a non-null empty list, mirroring the AgentRequest compact-ctor
        // contract (canonical-ctor normalises null → List.of()).
        MessageHandlerContext ctx = createContextWithMetadata("hello");

        Flux<AgentStreamEvent> stream = Flux.just(AgentStreamEvent.finalAnswer("hi", 1));
        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        when(agentExecutor.executeStream(captor.capture())).thenReturn(stream);

        actions.generateResponse(ctx);

        assertThat(captor.getValue().attachments()).isNotNull().isEmpty();
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
    @DisplayName("createCommand looks up aiGateway when agentExecutor is present but user disabled agent mode")
    void shouldLookupAiGatewayInCreateCommandWhenAgentExecutorPresentButUserDisabledAgentMode() {
        // Arrange: agentExecutor is non-null (wired in @BeforeEach), but user has agent mode OFF
        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setAgentModeEnabled(Boolean.FALSE);

        TelegramCommand command = mock(TelegramCommand.class);
        MessageHandlerContext ctx = new MessageHandlerContext(command, null, s -> {});
        ctx.setTelegramUser(telegramUser);

        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.THREAD_KEY_FIELD, "test-thread-key");
        ctx.setMetadata(metadata);

        AICommand aiCommand = mock(AICommand.class);
        when(aiCommand.modelCapabilities()).thenReturn(Set.of(ModelCapabilities.CHAT));
        when(aiRequestPipeline.prepareCommand(any(), any())).thenReturn(aiCommand);

        AIGateway aiGateway = mock(AIGateway.class);
        when(aiGatewayRegistry.getSupportedAiGateways(any())).thenReturn(List.of(aiGateway));

        // Act
        actions.createCommand(ctx);

        // Assert: gateway must be populated even though agentExecutor bean is present
        assertThat(ctx.getAiGateway()).isNotNull();
        assertThat(ctx.getAiGateway()).isEqualTo(aiGateway);
        verify(aiGatewayRegistry).getSupportedAiGateways(any());
        // The agent executor must not be invoked — the predicate routes to the gateway path
        verify(agentExecutor, never()).executeStream(any());
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
    @Disabled("Superseded by TelegramAgentStreamModel/TelegramMessageHandlerActionsStreamingTest model-view tests")
    @DisplayName("Two-message orchestration")
    class TwoMessageOrchestration {

        private static final Long CHAT_ID = 12345L;
        private static final int USER_MSG_ID = 100;
        private static final int STATUS_MSG_ID = 555;
        private static final int ANSWER_MSG_ID = 777;
        private static final String STATUS_THINKING_LINE = "💭 Thinking...";

        @Test
        @DisplayName("should open a fresh answer bubble on the first PARTIAL_ANSWER chunk")
        void shouldCreateStatusAndAnswerMessagesOnFirstPartialAnswer() {
            MessageHandlerContext ctx = createContextWithMessage("Ask",
                    Set.of(ModelCapabilities.WEB));

            // Both bubbles reply to the user message now — disambiguate via HTML content:
            // status carries the thinking marker, answer bubble does not.
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID),
                    argThat(html -> html != null && html.contains(STATUS_THINKING_LINE)),
                    eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID),
                    argThat(html -> html != null && !html.contains(STATUS_THINKING_LINE)),
                    eq(USER_MSG_ID), eq(true)))
                    .thenReturn(ANSWER_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.partialAnswer("First paragraph.", 1),
                    AgentStreamEvent.partialAnswer("\n\nSecond paragraph.", 1),
                    AgentStreamEvent.finalAnswer("First paragraph.\n\nSecond paragraph.", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            // Status bubble seeded with thinking line as a reply to the user.
            verify(messageSender, times(1)).sendHtmlAndGetId(eq(CHAT_ID),
                    argThat(html -> html != null && html.contains(STATUS_THINKING_LINE)),
                    eq(USER_MSG_ID), eq(true));

            // Answer bubble opened on the first PARTIAL_ANSWER (threaded reply to the user —
            // distinguished from the status bubble by the absence of the thinking marker).
            // The initial content carries only the first chunk; the second chunk arrives via
            // edits to the bubble below.
            verify(messageSender, times(1)).sendHtmlAndGetId(eq(CHAT_ID),
                    argThat(html -> html != null && !html.contains(STATUS_THINKING_LINE)
                            && html.contains("First paragraph.")),
                    eq(USER_MSG_ID), eq(true));

            // Status transitioned to "Answering…" when bubble opened.
            ArgumentCaptor<String> statusEditCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), statusEditCaptor.capture(), eq(true));
            assertThat(statusEditCaptor.getAllValues())
                    .anyMatch(html -> html.contains("ℹ️ Answering"));

            // Answer bubble received at least one edit (final flush enables link previews,
            // so the preview flag varies across streaming vs. force-flushed edits). The
            // second paragraph arrives to the bubble via these edits.
            ArgumentCaptor<String> answerEditCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(ANSWER_MSG_ID), answerEditCaptor.capture(), anyBoolean());
            assertThat(answerEditCaptor.getAllValues())
                    .anyMatch(html -> html.contains("Second paragraph."));

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

            // Status carries the thinking marker; the answer bubble send does not.
            // Both reply to the user message (P1: keep agent bubbles threaded).
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID),
                    argThat(html -> html != null && html.contains(STATUS_THINKING_LINE)),
                    eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID),
                    argThat(html -> html != null && !html.contains(STATUS_THINKING_LINE)),
                    eq(USER_MSG_ID), eq(true)))
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

            // Status carries the thinking marker; the answer bubble send does not.
            // Both reply to the user message (P1: keep agent bubbles threaded).
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID),
                    argThat(html -> html != null && html.contains(STATUS_THINKING_LINE)),
                    eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID),
                    argThat(html -> html != null && !html.contains(STATUS_THINKING_LINE)),
                    eq(USER_MSG_ID), eq(true)))
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

            // Status carries the thinking marker; the answer bubble send does not.
            // Both reply to the user message (P1: keep agent bubbles threaded).
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID),
                    argThat(html -> html != null && html.contains(STATUS_THINKING_LINE)),
                    eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID),
                    argThat(html -> html != null && !html.contains(STATUS_THINKING_LINE)),
                    eq(USER_MSG_ID), eq(true)))
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
                    .anyMatch(html -> html.contains("🔧 <b>Tool:</b>"));
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

            // Each iteration starts with a THINKING marker (null content) — this is what the
            // real agent loop emits at the start of every think() call. The marker triggers
            // an AppendFreshThinking which creates the "\n\n" boundary between iter-blocks
            // and prevents the next tool-call edit from wiping out the previous iteration.
            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.toolCall("web_search", "{\"q\":\"a\"}", 0),
                    AgentStreamEvent.observation("Found 3 items", 0),
                    AgentStreamEvent.thinking(1),
                    AgentStreamEvent.toolCall("web_search", "{\"q\":\"b\"}", 1),
                    AgentStreamEvent.observation("", 1),
                    AgentStreamEvent.thinking(2),
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
            assertThat(lastHtml).contains("<blockquote>📋 Tool result received</blockquote>");
            assertThat(lastHtml).contains("<blockquote>📋 No result</blockquote>");
            assertThat(lastHtml).contains("<blockquote>⚠️ Tool failed: Network timeout</blockquote>");
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
            assertThat(finalHtml).contains("🔧 <b>Tool:</b>");
            assertThat(finalHtml).contains("📋 Tool result received");
            assertThat(finalHtml).contains("<blockquote>📋 Tool result received</blockquote>");
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

            // Tool-call blocks include "\n\n🔧 Tool: …\nQuery: …" — 3 iterations, each
            // preceded by a THINKING marker that creates the "\n\n" boundary between them.
            // Combined length pushes the buffer past 120 chars; rotator cuts at a "\n\n"
            // boundary, sends the head as the finalized old status, starts a fresh message
            // for the tail.
            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.toolCall("web_search", "{\"q\":\"" + "x".repeat(20) + "\"}", 0),
                    AgentStreamEvent.observation("r", 0),
                    AgentStreamEvent.thinking(1),
                    AgentStreamEvent.toolCall("web_search", "{\"q\":\"" + "y".repeat(20) + "\"}", 1),
                    AgentStreamEvent.observation("r", 1),
                    AgentStreamEvent.thinking(2),
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

            // Status carries the thinking marker; the answer bubble send does not.
            // Both reply to the user message (P1: keep agent bubbles threaded).
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID),
                    argThat(html -> html != null && html.contains(STATUS_THINKING_LINE)),
                    eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID),
                    argThat(html -> html != null && !html.contains(STATUS_THINKING_LINE)),
                    eq(USER_MSG_ID), eq(true)))
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
            // stream-error handler). Preview flag varies across streaming vs. final edits.
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(ANSWER_MSG_ID), anyString(), anyBoolean());

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

        @Test
        @DisplayName("should render tool-call block with bold Tool/Query labels")
        void shouldRenderToolCallWithBoldLabels() {
            MessageHandlerContext ctx = createContextWithMessage("Ask",
                    Set.of(ModelCapabilities.WEB));
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.toolCall("web_search", "{\"q\":\"cats\"}", 0),
                    AgentStreamEvent.observation("ok", 0),
                    AgentStreamEvent.finalAnswer("done", 0));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            ArgumentCaptor<String> editCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), editCaptor.capture(), eq(true));
            String finalHtml = editCaptor.getValue();
            assertThat(finalHtml).contains("🔧 <b>Tool:</b> Searching the web");
            assertThat(finalHtml).contains("<b>Query:</b>");
            // The label is HTML bold — no unformatted "Tool:" or "Query:" leaking through.
            assertThat(finalHtml).doesNotContain("🔧 Tool:");
            assertThat(finalHtml).doesNotContain("\nQuery:");
        }

        @Test
        @DisplayName("should replace trailing reasoning line with tool-call block (visual chronology is time-based)")
        void shouldReplaceTrailingReasoningWithToolCallBlock() {
            MessageHandlerContext ctx = createContextWithMessage("Ask",
                    Set.of(ModelCapabilities.WEB));
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID), anyString(), eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);

            // Reasoning arrives first, then a tool call. The tool-call block replaces the
            // reasoning overlay — the previous state is kept visible by the paced flush
            // (throttle=0 in tests skips the sleep; pacing is observable in production).
            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.thinking("I need to check the benchmarks first.", 0),
                    AgentStreamEvent.toolCall("web_search", "{\"q\":\"benchmarks\"}", 0),
                    AgentStreamEvent.observation("ok", 0),
                    AgentStreamEvent.finalAnswer("done", 0));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            ArgumentCaptor<String> editCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(STATUS_MSG_ID), editCaptor.capture(), eq(true));
            String finalHtml = editCaptor.getValue();

            // Final buffer must have the tool-call block and the observation marker; the
            // reasoning overlay has been overwritten by the tool-call edit (as per spec).
            assertThat(finalHtml).contains("🔧 <b>Tool:</b>");
            assertThat(finalHtml).contains("📋 Tool result received");
            assertThat(finalHtml).contains("<blockquote>📋 Tool result received</blockquote>");
            assertThat(finalHtml).doesNotContain("check the benchmarks first.");
        }

        @Test
        @DisplayName("should convert Markdown in tentative answer bubble to HTML tags")
        void shouldConvertMarkdownInTentativeAnswerBubble() {
            MessageHandlerContext ctx = createContextWithMessage("Compare",
                    Set.of(ModelCapabilities.WEB));
            // Status carries the thinking marker; the answer bubble send does not.
            // Both reply to the user message (P1: keep agent bubbles threaded).
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID),
                    argThat(html -> html != null && html.contains(STATUS_THINKING_LINE)),
                    eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID),
                    argThat(html -> html != null && !html.contains(STATUS_THINKING_LINE)),
                    eq(USER_MSG_ID), eq(true)))
                    .thenReturn(ANSWER_MSG_ID);

            // A PARTIAL_ANSWER carrying **bold** and `code` markers crosses a paragraph
            // boundary — the tentative bubble opens. The opened bubble must carry HTML,
            // not raw Markdown.
            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.partialAnswer("First paragraph.\n\n", 0),
                    AgentStreamEvent.partialAnswer("**Bold** and `code`.", 0),
                    AgentStreamEvent.finalAnswer("First paragraph.\n\n**Bold** and `code`.", 0));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            ArgumentCaptor<String> answerCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(ANSWER_MSG_ID), answerCaptor.capture(), eq(true));
            String lastAnswerHtml = answerCaptor.getValue();
            assertThat(lastAnswerHtml).contains("<b>Bold</b>");
            assertThat(lastAnswerHtml).contains("<code>code</code>");
            assertThat(lastAnswerHtml).doesNotContain("**Bold**");
            assertThat(lastAnswerHtml).doesNotContain("`code`");
        }

        @Test
        @DisplayName("should drop pre-tool reasoning text from tentative buffer on TOOL_CALL " +
                "so it doesn't leak into the final answer")
        void shouldClearTentativeBufferOnToolCallSoPreToolReasoningDoesNotLeakIntoAnswer() {
            MessageHandlerContext ctx = createContextWithMessage(
                    "Compare Quarkus and Spring Boot in 2026",
                    Set.of(ModelCapabilities.WEB));
            // Status carries the thinking marker; the answer bubble send does not.
            // Both reply to the user message (P1: keep agent bubbles threaded).
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID),
                    argThat(html -> html != null && html.contains(STATUS_THINKING_LINE)),
                    eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID),
                    argThat(html -> html != null && !html.contains(STATUS_THINKING_LINE)),
                    eq(USER_MSG_ID), eq(true)))
                    .thenReturn(ANSWER_MSG_ID);

            // Reproduces the production scenario with model z-ai/glm-4.5v:
            // iter-0 — model emits pre-tool REASONING as ordinary PARTIAL_ANSWER text
            //   (no \n\n so no promotion), followed by a structured TOOL_CALL + OBSERVATION.
            // iter-2 — model emits the REAL final answer with a \n\n boundary.
            // Without the fix, the tentative-answer buffer would still contain the iter-0
            // reasoning when iter-2 PARTIAL_ANSWER appends; promotion opens the bubble with
            // both the reasoning AND the new answer concatenated.
            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.partialAnswer(
                            "To compare I need to find fresh benchmarks first.",
                            0),
                    AgentStreamEvent.toolCall("web_search", "{\"q\":\"benchmarks\"}", 0),
                    AgentStreamEvent.observation("found hits", 0),
                    AgentStreamEvent.thinking(1),
                    AgentStreamEvent.toolCall("fetch_url", "{\"url\":\"https://example\"}", 1),
                    AgentStreamEvent.observation("page body", 1),
                    AgentStreamEvent.thinking(2),
                    AgentStreamEvent.partialAnswer("Here is the real answer.\n\nSecond paragraph.", 2),
                    AgentStreamEvent.finalAnswer("Here is the real answer.\n\nSecond paragraph.", 2));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            // The answer bubble was opened and edited — collect every version that hit the wire.
            // Preview flag varies across streaming vs. final edits, so we match any boolean.
            ArgumentCaptor<String> answerCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(ANSWER_MSG_ID), answerCaptor.capture(), anyBoolean());
            String lastAnswerHtml = answerCaptor.getValue();

            // The real final answer must be present; the pre-tool reasoning text must NOT
            // appear in the final answer bubble in ANY edit.
            assertThat(lastAnswerHtml).contains("Here is the real answer.");
            assertThat(lastAnswerHtml).contains("Second paragraph.");
            for (String html : answerCaptor.getAllValues()) {
                assertThat(html)
                        .as("pre-tool reasoning must not leak into the answer bubble")
                        .doesNotContain("fresh benchmarks first");
            }
        }

        @Test
        @DisplayName("should enable link previews on the final edit of the answer bubble")
        void shouldEnableLinkPreviewsOnFinalAnswerEdit() {
            // During streaming the URL is typed character-by-character — preview resolution
            // would either fail or flicker. On the terminal force-flush (after FINAL_ANSWER),
            // the message is complete, so Telegram should render the preview card.
            MessageHandlerContext ctx = createContextWithMessage("Ask",
                    Set.of(ModelCapabilities.WEB));

            // Status carries the thinking marker; the answer bubble send does not.
            // Both reply to the user message (P1: keep agent bubbles threaded).
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID),
                    argThat(html -> html != null && html.contains(STATUS_THINKING_LINE)),
                    eq(USER_MSG_ID), eq(true)))
                    .thenReturn(STATUS_MSG_ID);
            when(messageSender.sendHtmlAndGetId(eq(CHAT_ID),
                    argThat(html -> html != null && !html.contains(STATUS_THINKING_LINE)),
                    eq(USER_MSG_ID), eq(true)))
                    .thenReturn(ANSWER_MSG_ID);

            Flux<AgentStreamEvent> stream = Flux.just(
                    AgentStreamEvent.thinking(0),
                    AgentStreamEvent.partialAnswer("Report available at https://example.com/r.", 1),
                    AgentStreamEvent.partialAnswer("\n\nSee the link above.", 1),
                    AgentStreamEvent.finalAnswer(
                            "Report available at https://example.com/r.\n\nSee the link above.", 1));
            when(agentExecutor.executeStream(any(AgentRequest.class))).thenReturn(stream);

            actions.generateResponse(ctx);

            // At least one edit of the answer bubble was made with disableWebPagePreview=false
            // (preview enabled) — that's the terminal force-flush.
            verify(messageSender, atLeastOnce())
                    .editHtml(eq(CHAT_ID), eq(ANSWER_MSG_ID), anyString(), eq(false));
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
