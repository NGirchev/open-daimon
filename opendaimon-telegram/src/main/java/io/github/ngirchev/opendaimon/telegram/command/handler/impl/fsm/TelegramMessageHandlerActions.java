package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentStrategy;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.ai.AIGateways;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.command.ChatAICommand;
import io.github.ngirchev.opendaimon.common.ai.command.FixedModelChatAICommand;
import io.github.ngirchev.opendaimon.common.ai.pipeline.AIRequestPipeline;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIStreamResponse;
import io.github.ngirchev.opendaimon.common.exception.DocumentContentNotExtractableException;
import io.github.ngirchev.opendaimon.common.exception.ModelGuardrailException;

import io.github.ngirchev.opendaimon.common.exception.UnsupportedModelCapabilityException;
import io.github.ngirchev.opendaimon.common.exception.UserMessageTooLongException;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.RequestType;
import io.github.ngirchev.opendaimon.common.model.ResponseStatus;
import io.github.ngirchev.opendaimon.common.model.ThinkingMode;
import io.github.ngirchev.opendaimon.common.service.AIGateway;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;
import io.github.ngirchev.opendaimon.common.service.AIUtils;
import io.github.ngirchev.opendaimon.common.service.OpenDaimonMessageService;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUserSession;
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
import io.github.ngirchev.opendaimon.telegram.service.RenderedUpdate;
import io.github.ngirchev.opendaimon.telegram.service.ReplyImageAttachmentService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramAgentStreamModel;
import io.github.ngirchev.opendaimon.telegram.service.TelegramAgentStreamView;
import io.github.ngirchev.opendaimon.telegram.service.TelegramHtmlEscaper;
import io.github.ngirchev.opendaimon.telegram.service.TelegramProgressBatcher;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserSessionService;
import io.github.ngirchev.opendaimon.telegram.service.ToolLabels;
import io.github.ngirchev.opendaimon.common.model.User;
import io.github.ngirchev.opendaimon.telegram.service.ChatSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.telegram.telegrambots.meta.api.objects.Message;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.github.ngirchev.opendaimon.common.ai.command.AICommand.*;
import static io.github.ngirchev.opendaimon.common.service.AIUtils.extractError;
import static io.github.ngirchev.opendaimon.common.service.AIUtils.retrieveMessage;

/**
 * Implementation of {@link MessageHandlerActions} for Telegram message processing.
 *
 * <p>Ports logic from {@code MessageTelegramCommandHandler.handleInner()} into discrete
 * FSM action methods. Each method corresponds to a single FSM transition action and
 * populates the {@link MessageHandlerContext} with results for subsequent transitions.
 *
 * <p>Error handling: actions catch expected exceptions and set error info on context
 * rather than throwing. The FSM routes to ERROR terminal state, and the handler
 * dispatches to the appropriate error handling method.
 *
 * <p><b>Construction:</b> manually instantiated in ~8 sites (prod auto-config, unit
 * tests, fixture IT config) because this class is not a Spring-scanned bean. When
 * changing the constructor signature, search for {@code new TelegramMessageHandlerActions}
 * across the module and {@code opendaimon-app/src/it/java} to update every site —
 * missing one produces a compile error only discovered at full build time.
 */
@Slf4j
@RequiredArgsConstructor
public class TelegramMessageHandlerActions implements MessageHandlerActions {

    /**
     * Opening line of the status message — seeded as soon as the agent run starts so the
     * user sees immediate feedback. Later replaced in place by the reasoning overlay or
     * by tool-call / observation markers that get appended as iterations progress.
     */
    private static final String STATUS_THINKING_LINE = "💭 Thinking...";

    /**
     * Escaped HTML placed into the tentative answer bubble on delete failure, instead of
     * deleting it. Standalone {@code <i>…</i>} is safe in parse_mode=HTML.
     */
    private static final String ROLLBACK_FALLBACK_HTML = "<i>(folded into reasoning)</i>";
    private static final String MISSING_TOOL_ARGUMENT = "missing";

    private final TelegramUserService telegramUserService;
    private final TelegramUserSessionService telegramUserSessionService;
    private final TelegramMessageService telegramMessageService;
    private final AIGatewayRegistry aiGatewayRegistry;
    private final OpenDaimonMessageService messageService;
    private final AIRequestPipeline aiRequestPipeline;
    private final TelegramProperties telegramProperties;
    private final ChatSettingsService chatSettingsService;
    private final PersistentKeyboardService persistentKeyboardService;
    private final ReplyImageAttachmentService replyImageAttachmentService;

    /** Callback for sending messages — provided by the handler (wraps TelegramBot API). */
    private final TelegramMessageSender messageSender;

    /** Agent executor — null when {@code open-daimon.agent.enabled=false}. */
    private final AgentExecutor agentExecutor;
    /** Telegram stream view — sends snapshots of the provider-neutral stream model. */
    private final TelegramAgentStreamView agentStreamView;
    /** Agent max iterations — only used when {@code agentExecutor} is non-null. */
    private final int agentMaxIterations;
    /**
     * Application-level default for agent mode. Mirrors {@code open-daimon.agent.enabled}.
     * Used as fallback when {@code TelegramUser.agentModeEnabled} is {@code null}.
     */
    private final boolean defaultAgentModeEnabled;

    @Override
    public void resolveUser(MessageHandlerContext ctx) {
        Message message = ctx.getMessage();
        if (message == null) {
            ctx.setErrorType(MessageHandlerErrorType.GENERAL);
            ctx.setException(new IllegalStateException("Message is required for message command"));
            return;
        }

        TelegramUser telegramUser = telegramUserService.getOrCreateUser(message.getFrom());
        ctx.setTelegramUser(telegramUser);
        // Ensure command carries the resolved internal user ID so that downstream
        // components (e.g. AICommandFactory → UserPriorityService) can determine
        // the correct user priority. TelegramBot sets this when creating the command,
        // but direct handler invocations (tests, coalescing) may leave it null.
        if (ctx.getCommand().userId() == null) {
            ctx.getCommand().userId(telegramUser.getId());
        }

        TelegramUserSession session = telegramUserSessionService.getOrCreateSession(telegramUser);
        ctx.setSession(session);

        log.debug("FSM resolveUser: userId={}", telegramUser.getId());
    }

    @Override
    public void validateInput(MessageHandlerContext ctx) {
        TelegramCommand command = ctx.getCommand();
        boolean hasNoText = command.userText() == null || command.userText().isBlank();
        boolean hasNoAttachments = command.attachments() == null || command.attachments().isEmpty();

        if (hasNoText && hasNoAttachments) {
            ctx.setHasInput(false);
            ctx.setErrorType(MessageHandlerErrorType.INPUT_EMPTY);
            log.debug("FSM validateInput: empty input");
        } else {
            ctx.setHasInput(true);
            log.debug("FSM validateInput: hasText={}, hasAttachments={}", !hasNoText, !hasNoAttachments);
        }
    }

    @Override
    public void saveMessage(MessageHandlerContext ctx) {
        TelegramCommand command = ctx.getCommand();
        TelegramUser telegramUser = ctx.getTelegramUser();
        TelegramUserSession session = ctx.getSession();
        Message message = ctx.getMessage();

        OpenDaimonMessage userMessage = telegramMessageService.saveUserMessage(
                telegramUser, session, command.userText(),
                RequestType.TEXT, null, command.attachments(),
                command.telegramId(), message.getMessageId());

        ctx.setUserMessage(userMessage);
        ctx.setThread(userMessage.getThread());
        ctx.setAssistantRole(userMessage.getAssistantRole());

        log.info("FSM saveMessage: thread={}, role={}(v{})",
                userMessage.getThread().getThreadKey(),
                userMessage.getAssistantRole().getId(),
                userMessage.getAssistantRole().getVersion());
    }

    @Override
    public void prepareMetadata(MessageHandlerContext ctx) {
        TelegramCommand command = ctx.getCommand();
        TelegramUser telegramUser = ctx.getTelegramUser();
        ConversationThread thread = ctx.getThread();

        // Resolve reply image attachments
        Message replyToMessage = ctx.getMessage().getReplyToMessage();
        if (replyToMessage != null && !command.hasAttachments()) {
            List<Attachment> replyAttachments = replyImageAttachmentService
                    .resolveReplyImageAttachments(replyToMessage, thread);
            for (Attachment att : replyAttachments) {
                command.addAttachment(att);
            }
        }

        // Build metadata map
        Map<String, String> metadata = new HashMap<>();
        metadata.put(THREAD_KEY_FIELD, thread.getThreadKey());
        metadata.put(ASSISTANT_ROLE_ID_FIELD, ctx.getAssistantRole().getId().toString());
        metadata.put(USER_ID_FIELD, telegramUser.getId().toString());
        metadata.put(ROLE_FIELD, withTelegramBotIdentity(ctx.getAssistantRole().getContent()));
        User settingsOwner = resolveOwner(ctx, telegramUser);
        String ownerLanguage = settingsOwner.getLanguageCode() != null
                ? settingsOwner.getLanguageCode() : telegramUser.getLanguageCode();
        if (ownerLanguage != null) {
            metadata.put(LANGUAGE_CODE_FIELD, ownerLanguage);
        }
        chatSettingsService.getPreferredModel(settingsOwner)
                .ifPresent(modelId -> metadata.put(PREFERRED_MODEL_ID_FIELD, modelId));

        // Add RAG document IDs from previous turns
        List<String> ragDocIds = messageService.findRagDocumentIds(thread);
        if (!ragDocIds.isEmpty()) {
            metadata.put(RAG_DOCUMENT_IDS_FIELD, String.join(",", ragDocIds));
        }

        ctx.setMetadata(metadata);
        ctx.setStartTime(System.currentTimeMillis());

        log.debug("FSM prepareMetadata: threadKey={}", thread.getThreadKey());
    }

    @Override
    public void createCommand(MessageHandlerContext ctx) {
        TelegramCommand command = ctx.getCommand();
        Map<String, String> metadata = ctx.getMetadata();

        try {
            AICommand aiCommand = aiRequestPipeline.prepareCommand(command, metadata);
            ctx.setAiCommand(aiCommand);
            ctx.setModelCapabilities(aiCommand.modelCapabilities());

            // Gateway path is taken when agent bean is absent OR user disabled agent mode —
            // mirror the predicate used in generateResponse to keep FSM state consistent.
            if (agentExecutor == null || !isAgentModeEnabledForUser(ctx)) {
                AIGateway aiGateway = aiGatewayRegistry.getSupportedAiGateways(aiCommand)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException(AIUtils.NO_SUPPORTED_AI_GATEWAY));
                ctx.setAiGateway(aiGateway);
            }

            log.debug("FSM createCommand: capabilities={}, agentPath={}",
                    aiCommand.modelCapabilities(),
                    agentExecutor != null && isAgentModeEnabledForUser(ctx));
        } catch (UserMessageTooLongException e) {
            ctx.setErrorType(MessageHandlerErrorType.MESSAGE_TOO_LONG);
            ctx.setException(e);
        } catch (DocumentContentNotExtractableException e) {
            ctx.setErrorType(MessageHandlerErrorType.DOCUMENT_NOT_EXTRACTABLE);
            ctx.setException(e);
        } catch (UnsupportedModelCapabilityException e) {
            ctx.setErrorType(MessageHandlerErrorType.UNSUPPORTED_CAPABILITY);
            ctx.setException(e);
        } catch (Exception e) {
            handleGeneralException(ctx, e);
        }
    }

    @Override
    public void generateResponse(MessageHandlerContext ctx) {
        if (agentExecutor != null && isAgentModeEnabledForUser(ctx)) {
            generateAgentResponse(ctx);
        } else {
            generateGatewayResponse(ctx);
        }
    }

    /**
     * Returns {@code true} when the user has agent mode enabled.
     * Uses the per-user flag if set; falls back to {@code defaultAgentModeEnabled}.
     */
    private boolean isAgentModeEnabledForUser(MessageHandlerContext ctx) {
        TelegramUser user = ctx.getTelegramUser();
        if (user == null) {
            return defaultAgentModeEnabled;
        }
        User owner = resolveOwner(ctx, user);
        Boolean flag = owner.getAgentModeEnabled();
        return flag != null ? flag : defaultAgentModeEnabled;
    }

    /**
     * Safe owner resolution: returns {@code ctx.getCommand().settingsOwnerOr(fallback)} if the
     * command exposes a non-null owner, otherwise falls back to {@code fallback} (the invoker).
     * Guards against test mocks that return {@code null} from {@code settingsOwnerOr}.
     */
    private static User resolveOwner(MessageHandlerContext ctx, TelegramUser fallback) {
        TelegramCommand cmd = ctx.getCommand();
        if (cmd == null) return fallback;
        User owner = cmd.settingsOwnerOr(fallback);
        return owner != null ? owner : fallback;
    }

    private void generateAgentResponse(MessageHandlerContext ctx) {
        TelegramCommand command = ctx.getCommand();
        Map<String, String> metadata = ctx.getMetadata();
        AICommand aiCommand = ctx.getAiCommand();
        Long chatId = command.telegramId();

        try {
            Set<ModelCapabilities> capabilities = ctx.getModelCapabilities();
            boolean hasToolAccess = capabilities != null
                    && (capabilities.contains(ModelCapabilities.WEB)
                        || capabilities.contains(ModelCapabilities.AUTO));
            AgentStrategy strategy = hasToolAccess ? AgentStrategy.AUTO : AgentStrategy.SIMPLE;
            log.info("FSM generateAgentResponse: capabilities={}, strategy={}", capabilities, strategy);

            // Prefer pipeline-prepared text (RAG-augmented, document-only fallback,
            // attachment-aware) over the raw Telegram text, so agent mode matches
            // the normal gateway path for document/RAG follow-up scenarios.
            String agentTask = aiCommand != null && aiCommand.userRole() != null
                    ? aiCommand.userRole()
                    : command.userText();

            // Forward image attachments into the agent path so the first user message
            // in the agent prompt carries Media — without this, vision-capable models
            // are selected (capabilities=[CHAT, VISION]) but receive only the caption
            // text and answer "are there any images?" (see SPRING_AI_MODULE.md, agent
            // path media propagation). Source must be aiCommand.attachments() (the
            // pipeline-processed list, mirroring SpringAIGateway:383-387), not the raw
            // command.attachments(): for an image-only PDF the pipeline rendered each
            // page into an IMAGE attachment in mutableAttachments, and the agent path
            // must see those rendered pages — not the original PDF bytes that
            // toImageMedia() then discards as non-IMAGE. Both AI-command shapes carry
            // the pipeline-processed list — DefaultAICommandFactory returns
            // FixedModelChatAICommand whenever a preferred model is fixed, otherwise
            // ChatAICommand — so we must inspect both before falling back to raw.
            List<Attachment> agentAttachments;
            if (aiCommand instanceof ChatAICommand chat && chat.attachments() != null) {
                agentAttachments = chat.attachments();
            } else if (aiCommand instanceof FixedModelChatAICommand fixed && fixed.attachments() != null) {
                agentAttachments = fixed.attachments();
            } else if (command.attachments() != null) {
                agentAttachments = command.attachments();
            } else {
                agentAttachments = List.of();
            }
            AgentRequest request = new AgentRequest(
                    agentTask,
                    metadata.get(THREAD_KEY_FIELD),
                    metadata,
                    agentMaxIterations,
                    Set.of(),
                    strategy,
                    agentAttachments
            );

            TelegramAgentStreamModel streamModel = new TelegramAgentStreamModel(
                    isThinkingSilent(ctx), isThinkingPreserved(ctx));
            syncAgentStreamContext(ctx, streamModel);
            agentStreamView.flush(ctx, streamModel, true);

            // Stream agent events through a provider-neutral model first. PARTIAL_ANSWER
            // chunks are candidates inside that model until the terminal event confirms
            // that the current iteration is the user-visible answer. Telegram receives
            // periodic snapshots of message1 (status) and only gets message2 after the
            // final answer is known.
            AgentStreamEvent lastEvent = agentExecutor.executeStream(request)
                    .concatMap(event -> handleAgentStreamModelEvent(ctx, streamModel, event).thenReturn(event))
                    .onErrorResume(err -> {
                        log.warn("FSM agentStreamEvent: stream errored — finalizing model", err);
                        String msg = err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName();
                        streamModel.apply(AgentStreamEvent.error(msg, streamModel.currentIteration()));
                        syncAgentStreamContext(ctx, streamModel);
                        agentStreamView.flush(ctx, streamModel, true);
                        return reactor.core.publisher.Flux.empty();
                    })
                    .blockLast();

            agentStreamView.flush(ctx, streamModel, true);

            extractAgentResult(ctx, lastEvent);

            if (ctx.hasResponse()) {
                String answerText = ctx.getResponseText().orElse("");
                if (!answerText.isEmpty()) {
                    streamModel.confirmAnswer(answerText);
                    if (!agentStreamView.flushFinal(ctx, streamModel)) {
                        ctx.setErrorType(MessageHandlerErrorType.TELEGRAM_DELIVERY_FAILED);
                        ctx.setException(new TelegramDeliveryFailedException(
                                "Final answer could not be delivered to Telegram"));
                        log.error("FSM generateAgentResponse: final answer delivery failed for chatId={}", chatId);
                        return;
                    }
                    log.info("FSM generateAgentResponse: final answer delivered via Telegram stream view, textLength={}",
                            answerText.length());
                }
                ctx.setAlreadySentInStream(true);
            } else {
                log.warn("FSM generateAgentResponse: no response text after extractAgentResult");
            }
        } catch (Exception e) {
            handleGeneralException(ctx, e);
        }
    }

    private Mono<Void> handleAgentStreamModelEvent(MessageHandlerContext ctx,
                                                   TelegramAgentStreamModel streamModel,
                                                   AgentStreamEvent event) {
        if (event.type() == AgentStreamEvent.EventType.PARTIAL_ANSWER) {
            log.debug("FSM agentStreamEvent: type={}, iteration={}, contentLength={}",
                    event.type(), event.iteration(),
                    event.content() != null ? event.content().length() : 0);
        } else {
            log.info("FSM agentStreamEvent: type={}, iteration={}, contentLength={}",
                    event.type(), event.iteration(),
                    event.content() != null ? event.content().length() : 0);
        }
        if (event.type() == AgentStreamEvent.EventType.METADATA && event.content() != null) {
            ctx.setResponseModel(event.content());
            return Mono.empty();
        }
        streamModel.apply(event);
        syncAgentStreamContext(ctx, streamModel);
        agentStreamView.flush(ctx, streamModel);
        return Mono.empty();
    }

    private void syncAgentStreamContext(MessageHandlerContext ctx, TelegramAgentStreamModel streamModel) {
        ctx.setCurrentIteration(streamModel.currentIteration());
        ctx.setToolCallSeenThisIteration(streamModel.isToolCallSeenThisIteration());
        ctx.getStatusBuffer().setLength(0);
        ctx.getStatusBuffer().append(streamModel.statusHtml());
    }

    /**
     * Collapses any whitespace run (spaces, tabs, newlines) in an overlay line into a
     * single space. Required because {@link #replaceTrailingThinkingLineWithEscaped}
     * uses {@code \n\n} as the boundary between completed status blocks and the current
     * trailing line — if the trailing {@code <i>…</i>} overlay itself contains
     * {@code \n\n}, the next boundary search cuts inside the tags and the closing
     * {@code </i>} is lost, producing invalid HTML that Telegram rejects with a parse
     * error (the fallback sends the message unformatted, so users see a literal
     * {@code <i>}).
     */
    private static String collapseToSingleLine(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return s.replaceAll("\\s+", " ").trim();
    }

    private Mono<Void> applyUpdate(MessageHandlerContext ctx, RenderedUpdate update) {
        return switch (update) {
            case RenderedUpdate.ReplaceTrailingThinkingLine r -> Mono.fromRunnable(() -> {
                if (isThinkingSilent(ctx)) {
                    return;
                }
                String reasoningHtml = "<i>"
                        + collapseToSingleLine(TelegramHtmlEscaper.escape(r.reasoning()))
                        + "</i>";
                // Multi-iteration SHOW_ALL path: when the buffer's trailing content is
                // NOT a thinking placeholder or a prior <i>…</i> overlay (i.e. an
                // observation `</blockquote>` or a `🔧 Tool:` block ended the previous
                // iteration), a new iteration's reasoning must be APPENDED as a new
                // paragraph rather than REPLACE the last paragraph — otherwise the
                // previous iteration's tool block and observation get erased.
                String current = ctx.getStatusBuffer().toString();
                boolean trailingIsOverlay = current.endsWith("</i>")
                        || current.endsWith(STATUS_THINKING_LINE);
                if (trailingIsOverlay) {
                    replaceTrailingThinkingLineWithEscaped(ctx, reasoningHtml, /*forceFlush=*/ false);
                } else {
                    appendToStatusBuffer(ctx, "\n\n" + reasoningHtml, /*forceFlush=*/ false);
                }
            });
            case RenderedUpdate.AppendFreshThinking ignored -> Mono.fromRunnable(() -> {
                if (isThinkingSilent(ctx)) {
                    return;
                }
                appendToStatusBuffer(ctx, "\n\n" + STATUS_THINKING_LINE, /*forceFlush=*/ false);
            });
            case RenderedUpdate.AppendToolCall tc -> isThinkingSilent(ctx)
                    ? Mono.empty()
                    : appendToolCallBlock(ctx, tc.toolName(), tc.args());
            case RenderedUpdate.AppendObservation obs -> isThinkingSilent(ctx)
                    ? Mono.empty()
                    : appendObservationMarker(ctx, obs.kind(), obs.errorSummary());
            case RenderedUpdate.AppendErrorToStatus err -> Mono.fromRunnable(() -> {
                if (isThinkingSilent(ctx)) {
                    return;
                }
                appendToStatusBuffer(ctx,
                        "\n\n❌ Error: " + TelegramHtmlEscaper.escape(err.message()),
                        /*forceFlush=*/ true);
            });
            case RenderedUpdate.RollbackAndAppendToolCall rb -> isThinkingSilent(ctx)
                    ? Mono.empty()
                    : rollbackAndAppendToolCall(ctx, rb.toolName(), rb.args(), rb.foldedProse());
            case RenderedUpdate.NoOp ignored -> Mono.empty();
        };
    }

    // --- Status message helpers ---

    /**
     * Sends the initial {@code 💭 Thinking...} status message (once per agent run) and
     * seeds {@link MessageHandlerContext#getStatusBuffer()} with its pre-escaped HTML so
     * subsequent edits just overwrite the whole buffer. If the send fails the buffer
     * still carries the text and later edit attempts short-circuit.
     */
    private boolean isThinkingSilent(MessageHandlerContext ctx) {
        TelegramUser user = ctx.getTelegramUser();
        if (user == null) {
            return false;
        }
        User owner = resolveOwner(ctx, user);
        return owner.getThinkingMode() == ThinkingMode.SILENT;
    }

    private boolean isThinkingPreserved(MessageHandlerContext ctx) {
        TelegramUser user = ctx.getTelegramUser();
        if (user == null) {
            return false;
        }
        User owner = resolveOwner(ctx, user);
        return owner.getThinkingMode() == ThinkingMode.SHOW_ALL;
    }

    private void ensureStatusMessage(MessageHandlerContext ctx) {
        if (ctx.getStatusMessageId() != null) {
            return;
        }
        Long chatId = ctx.getCommand().telegramId();
        TelegramUser user = ctx.getTelegramUser();
        User owner = user != null ? resolveOwner(ctx, user) : null;
        boolean silent = owner != null && owner.getThinkingMode() == ThinkingMode.SILENT;
        log.info("ensureStatusMessage: telegramId={}, thinkingMode={}, silent={}",
                user != null ? user.getTelegramId() : null,
                owner != null ? owner.getThinkingMode() : "null-owner",
                silent);
        // SILENT: do NOT create a status message at all. The user's intent is radical
        // silence — no thinking placeholder, no tool blocks, no observations in a
        // running log. The final answer is delivered as a fresh message through the
        // "no tentative bubble opened" branch in generateAgentResponse. All applyUpdate
        // cases that mutate the status buffer also no-op for SILENT users, so nothing
        // ever tries to edit this non-existent status message.
        if (silent) {
            ctx.setCurrentIteration(0);
            return;
        }
        ctx.getStatusBuffer().append(STATUS_THINKING_LINE);
        // Seed iteration 0 so the first null-content THINKING event isn't treated as a
        // rollover — otherwise the renderer would duplicate the thinking line. A new
        // AppendFreshThinking still fires when iteration 1 starts.
        ctx.setCurrentIteration(0);
        Integer sentId = messageSender.sendHtmlAndGetId(
                chatId, ctx.getStatusBuffer().toString(), ctx.consumeNextReplyToMessageId(), true);
        if (sentId != null) {
            ctx.setStatusMessageId(sentId);
            ctx.markStatusEdited();
            ctx.setAlreadySentInStream(true);
            log.info("FSM agentStream: status message created id={}", sentId);
        } else {
            log.warn("FSM agentStream: status message send failed — later edits will no-op");
        }
    }

    private void appendToStatusBuffer(MessageHandlerContext ctx, String escapedHtml, boolean forceFlush) {
        ctx.getStatusBuffer().append(escapedHtml);
        rotateStatusIfNeeded(ctx);
        editStatusThrottled(ctx, forceFlush);
    }

    /**
     * Replaces the trailing thinking/reasoning line in the status buffer. The trailing line
     * is either {@link #STATUS_THINKING_LINE} or a prior {@code <i>…</i>} overlay — found by
     * locating the last {@code \n\n} boundary and taking everything after it.
     */
    private void replaceTrailingThinkingLineWithEscaped(MessageHandlerContext ctx,
                                                        String newTrailingLineEscaped,
                                                        boolean forceFlush) {
        StringBuilder buf = ctx.getStatusBuffer();
        int lastBoundary = buf.lastIndexOf("\n\n");
        int cut = lastBoundary >= 0 ? lastBoundary + 2 : 0;
        buf.setLength(cut);
        buf.append(newTrailingLineEscaped);
        rotateStatusIfNeeded(ctx);
        editStatusThrottled(ctx, forceFlush);
    }

    private Mono<Void> appendToolCallBlock(MessageHandlerContext ctx, String toolName, String args) {
        String label = ToolLabels.label(toolName);
        String escapedArgs = args == null || args.isBlank()
                ? ""
                : TelegramHtmlEscaper.escape(ToolLabels.truncateArg(args));
        String blockBody = escapedArgs.isEmpty()
                ? "🔧 <b>Tool:</b> " + label + "\n<b>Query:</b> " + MISSING_TOOL_ARGUMENT
                : "🔧 <b>Tool:</b> " + label + "\n<b>Query:</b> " + escapedArgs;
        // Per spec §"Iteration flow": the tool call replaces the trailing thinking/reasoning
        // line — visual chronology "thinking → tool call → result" comes from TIME, not space.
        // The pacedForceFlushStatus call below guarantees the previous edit (placeholder or
        // reasoning overlay) has been visible on screen for at least one throttle window
        // before the tool-call block overwrites it. Without that pacing, a model that
        // emits a structured tool call without preceding text would replace "💭 Thinking..."
        // within the same tick and the user would never see the thinking state at all.
        //
        // When the per-user thinking-preserve flag is ON (set via /thinking command),
        // the reasoning line that arrived between `cut` and the current buffer end is
        // kept above the tool-call block so the user can read
        // "model thought → called that tool" in the final message.
        TelegramUser user = ctx.getTelegramUser();
        User preserveOwner = user != null ? resolveOwner(ctx, user) : null;
        boolean preserve = preserveOwner != null && preserveOwner.getThinkingMode() == ThinkingMode.SHOW_ALL;
        log.info("appendToolCallBlock: telegramId={}, thinkingMode={}, preserveReasoningAbove={}",
                user != null ? user.getTelegramId() : null,
                preserveOwner != null ? preserveOwner.getThinkingMode() : "null-owner",
                preserve);
        StringBuilder buf = ctx.getStatusBuffer();
        int lastBoundary = buf.lastIndexOf("\n\n");
        int cut = lastBoundary >= 0 ? lastBoundary + 2 : 0;
        if (preserve) {
            // Preserve the reasoning snippet. Ensure the block starts on its own paragraph.
            if (buf.length() > cut && buf.charAt(buf.length() - 1) != '\n') {
                buf.append("\n\n");
            }
            buf.append(blockBody);
        } else {
            buf.setLength(cut);
            buf.append(blockBody);
        }
        rotateStatusIfNeeded(ctx);
        return pacedForceFlushStatus(ctx);
    }

    private Mono<Void> appendObservationMarker(MessageHandlerContext ctx,
                                               RenderedUpdate.ObservationKind kind,
                                               String escapedErrorSummary) {
        String body = switch (kind) {
            case RESULT -> "📋 Tool result received";
            case EMPTY -> "📋 No result";
            case FAILED -> "⚠️ Tool failed: " + TelegramHtmlEscaper.escape(escapedErrorSummary);
        };
        ctx.getStatusBuffer().append("\n<blockquote>").append(body).append("</blockquote>");
        rotateStatusIfNeeded(ctx);
        return pacedForceFlushStatus(ctx);
    }

    /**
     * Waits until at least one throttle window has elapsed since the last status edit, then
     * pushes the current buffer to Telegram. Used for transitions between iteration phases
     * (thinking → tool call → observation) to give the user time to visually register each
     * state — the throttle interval ({@code open-daimon.telegram.agent-stream-edit-min-interval-ms})
     * doubles as the minimum paced gap between phase-transition edits.
     *
     * <p>Returns {@code Mono<Void>} — callers must subscribe (e.g. via {@code concatMap}) to
     * activate the delay. The delay runs on Reactor's timer scheduler so no Reactor worker
     * thread is blocked; this fixes the H9 thread-starvation issue with {@code Thread.sleep}.
     *
     * <p>When {@code throttleMs == 0} (test fixtures typically set this to disable throttling),
     * no delay is inserted and the helper degrades to a plain synchronous force flush wrapped in
     * {@code Mono.fromRunnable}.
     */
    private Mono<Void> pacedForceFlushStatus(MessageHandlerContext ctx) {
        long throttleMs = telegramProperties.getAgentStreamEditMinIntervalMs();
        long sinceLast = System.currentTimeMillis() - ctx.getLastStatusEditAtMs();
        long delayMs = throttleMs > 0 ? throttleMs - sinceLast : 0;
        if (delayMs > 0) {
            return Mono.delay(Duration.ofMillis(delayMs))
                    .then(Mono.fromRunnable(() -> editStatusThrottled(ctx, /*forceFlush=*/ true)));
        }
        return Mono.fromRunnable(() -> editStatusThrottled(ctx, /*forceFlush=*/ true));
    }

    /**
     * Tentative answer turned out to be reasoning: delete the bubble (or, on failure, edit
     * it to a graceful fallback so the user isn't left with stale content), fold the prose
     * into the status transcript as a reasoning line, and append a tool-call block.
     */
    private Mono<Void> rollbackAndAppendToolCall(MessageHandlerContext ctx, String toolName,
                                                  String args, String foldedProse) {
        Long chatId = ctx.getCommand().telegramId();
        Integer id = ctx.getTentativeAnswerMessageId();
        if (id != null) {
            boolean deleted = messageSender.deleteMessage(chatId, id);
            if (!deleted) {
                try {
                    messageSender.editHtml(chatId, id, ROLLBACK_FALLBACK_HTML, true);
                } catch (RuntimeException ex) {
                    log.warn("FSM agentStream: rollback fallback edit failed for id={}", id, ex);
                }
            }
        }
        String foldedOverlay = "<i>" + collapseToSingleLine(foldedProse) + "</i>";
        replaceTrailingThinkingLineWithEscaped(ctx, foldedOverlay, /*forceFlush=*/ true);
        ctx.resetTentativeAnswer();
        return appendToolCallBlock(ctx, toolName, args);
    }

    // --- Shared edit/rotate plumbing ---

    /**
     * Pushes the current status buffer to Telegram. Obeys the edit-interval throttle unless
     * {@code forceFlush} is set. First call also seeds {@link MessageHandlerContext#getStatusMessageId()}
     * when it is still {@code null} (e.g. {@link #ensureStatusMessage} failed earlier).
     */
    private void editStatusThrottled(MessageHandlerContext ctx, boolean forceFlush) {
        Integer id = ctx.getStatusMessageId();
        String html = ctx.getStatusBuffer().toString();
        if (html.isEmpty()) {
            return;
        }
        Long chatId = ctx.getCommand().telegramId();
        if (id == null) {
            Integer sentId = messageSender.sendHtmlAndGetId(chatId, html, ctx.consumeNextReplyToMessageId(), true);
            if (sentId != null) {
                ctx.setStatusMessageId(sentId);
                ctx.markStatusEdited();
                ctx.setAlreadySentInStream(true);
            }
            return;
        }
        long debounceMs = telegramProperties.getAgentStreamEditMinIntervalMs();
        if (!TelegramProgressBatcher.shouldFlush(
                ctx.getLastStatusEditAtMs(), System.currentTimeMillis(), debounceMs, forceFlush)) {
            return;
        }
        messageSender.editHtml(chatId, id, html, true);
        ctx.markStatusEdited();
        ctx.setAlreadySentInStream(true);
    }

    /**
     * If the status buffer exceeded {@code maxMessageLength}, cut it at a graceful boundary,
     * send the head as the now-finalized previous status, and start a fresh status message
     * for the tail (the buffer is mutated to hold the tail).
     */
    private void rotateStatusIfNeeded(MessageHandlerContext ctx) {
        int maxLength = telegramProperties.getMaxMessageLength();
        TelegramProgressBatcher.selectContentToFlush(ctx.getStatusBuffer(), maxLength)
                .ifPresent(head -> {
                    Long chatId = ctx.getCommand().telegramId();
                    Integer oldId = ctx.getStatusMessageId();
                    if (oldId != null) {
                        messageSender.editHtml(chatId, oldId, head, true);
                    }
                    Integer nextId = messageSender.sendHtmlAndGetId(
                            chatId, ctx.getStatusBuffer().toString(), null, true);
                    if (nextId != null) {
                        ctx.setStatusMessageId(nextId);
                        ctx.markStatusEdited();
                        ctx.setAlreadySentInStream(true);
                    }
                });
    }

    private void extractAgentResult(MessageHandlerContext ctx, AgentStreamEvent lastEvent) {
        if (lastEvent == null) {
            ctx.setErrorType(MessageHandlerErrorType.EMPTY_RESPONSE);
            return;
        }

        log.info("FSM generateAgentResponse: terminalEvent={}, iteration={}",
                lastEvent.type(), lastEvent.iteration());

        if (lastEvent.type() == AgentStreamEvent.EventType.FINAL_ANSWER
                && lastEvent.content() != null) {
            ctx.setResponseText(lastEvent.content());
        } else if (lastEvent.type() == AgentStreamEvent.EventType.MAX_ITERATIONS
                && lastEvent.content() != null) {
            ctx.setResponseText(lastEvent.content());
        } else if (lastEvent.type() == AgentStreamEvent.EventType.ERROR) {
            ctx.setErrorType(MessageHandlerErrorType.GENERAL);
            ctx.setException(new RuntimeException(lastEvent.content()));
        } else if (!ctx.hasResponse()) {
            ctx.setErrorType(MessageHandlerErrorType.EMPTY_RESPONSE);
        }
    }

    /**
     * Splits text by double newlines (paragraphs), converts each to HTML,
     * and sends via the provided sender. Respects Telegram max message length.
     */
    private void sendTextByParagraphs(String text, java.util.function.Consumer<String> sender) {
        int maxLength = telegramProperties.getMaxMessageLength();
        String[] paragraphs = text.split("\n\n");
        StringBuilder buffer = new StringBuilder();

        for (String paragraph : paragraphs) {
            // Split a single oversized paragraph on sentence/word/hard boundaries
            // so no outgoing chunk exceeds maxLength. Mirrors AIUtils.splitBlockByMaxLength.
            while (paragraph.length() > maxLength) {
                if (!buffer.isEmpty()) {
                    sender.accept(AIUtils.convertMarkdownToHtml(buffer.toString().trim()));
                    buffer.setLength(0);
                }
                int splitAt = AIUtils.findSplitPoint(paragraph, maxLength);
                sender.accept(AIUtils.convertMarkdownToHtml(paragraph.substring(0, splitAt).trim()));
                paragraph = paragraph.substring(splitAt);
            }
            if (buffer.length() + paragraph.length() + 2 > maxLength && !buffer.isEmpty()) {
                sender.accept(AIUtils.convertMarkdownToHtml(buffer.toString().trim()));
                buffer.setLength(0);
            }
            if (!buffer.isEmpty()) {
                buffer.append("\n\n");
            }
            buffer.append(paragraph);
        }

        if (!buffer.isEmpty()) {
            sender.accept(AIUtils.convertMarkdownToHtml(buffer.toString().trim()));
        }
    }

    private void generateGatewayResponse(MessageHandlerContext ctx) {
        TelegramCommand command = ctx.getCommand();
        Message message = ctx.getMessage();
        AICommand aiCommand = ctx.getAiCommand();
        AIGateway aiGateway = ctx.getAiGateway();

        try {
            AIResponse aiResponse;
            try {
                aiResponse = aiGateway.generateResponse(aiCommand);
                extractResponseContext(ctx, aiResponse, command, message);
            } catch (ModelGuardrailException e) {
                // Guardrail recovery: clear preference, rebuild command, retry.
                // FixedModelChatAICommand stores fixedModelId as an immutable record field,
                // so simply removing PREFERRED_MODEL_ID_FIELD from metadata does not switch
                // the gateway to auto-routing — we must ask the pipeline to rebuild the
                // command, which will produce a ChatAICommand when no preferred model is set.
                log.warn("FSM generateResponse: guardrail error for model={}, retrying",
                        e.getModelId());
                messageSender.sendNotification(command.telegramId(),
                        "common.error.model.guardrail", command.languageCode(), e.getModelId());
                User guardrailOwner = resolveOwner(ctx, ctx.getTelegramUser());
                chatSettingsService.clearPreferredModel(guardrailOwner);
                Map<String, String> metadata = aiCommand.metadata();
                metadata.remove(PREFERRED_MODEL_ID_FIELD);
                aiCommand = aiRequestPipeline.prepareCommand(command, metadata);
                ctx.setAiCommand(aiCommand);
                aiGateway = aiGatewayRegistry.getSupportedAiGateways(aiCommand)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException(AIUtils.NO_SUPPORTED_AI_GATEWAY));
                ctx.setAiGateway(aiGateway);
                aiResponse = aiGateway.generateResponse(aiCommand);
                extractResponseContext(ctx, aiResponse, command, message);
            }

            // Retry once on empty content
            if (!ctx.hasResponse()) {
                log.debug("FSM generateResponse: empty content, retrying once");
                aiResponse = aiGateway.generateResponse(aiCommand);
                extractResponseContext(ctx, aiResponse, command, message);
            }

            if (!ctx.hasResponse()) {
                ctx.setErrorType(MessageHandlerErrorType.EMPTY_RESPONSE);
            }
        } catch (Exception e) {
            handleGeneralException(ctx, e);
        }
    }

    @Override
    public void saveResponse(MessageHandlerContext ctx) {
        String responseText = ctx.getResponseText().orElseThrow();
        TelegramUser telegramUser = ctx.getTelegramUser();
        long processingTime = System.currentTimeMillis() - ctx.getStartTime();
        AICommand aiCommand = ctx.getAiCommand();

        // Update RAG metadata if new documents were processed
        String newRagDocIds = aiCommand.metadata().get(RAG_DOCUMENT_IDS_FIELD);
        String newRagFilenames = aiCommand.metadata().get(RAG_FILENAMES_FIELD);
        if (newRagFilenames != null && newRagDocIds != null) {
            messageService.updateRagMetadata(ctx.getUserMessage(),
                    Arrays.asList(newRagDocIds.split(",")),
                    Arrays.asList(newRagFilenames.split(",")));
        }

        // Save assistant message
        var assistantMessage = telegramMessageService.saveAssistantMessage(
                telegramUser,
                responseText,
                ctx.getModelCapabilities().toString(),
                ctx.getAssistantRole().getContent(),
                (int) processingTime,
                ctx.getUsefulResponseData(),
                ctx.getThread());
        messageService.updateMessageStatus(assistantMessage, ResponseStatus.SUCCESS);

        // Update thread reference from saved message (has up-to-date totalTokens)
        ctx.setThread(assistantMessage.getThread());

        log.info("FSM saveResponse: model={}, processingTime={}ms",
                ctx.getResponseModel(), processingTime);
    }

    // --- Private helpers ---

    private void extractResponseContext(MessageHandlerContext ctx, AIResponse aiResponse,
                                         TelegramCommand command, Message message) {
        ctx.setAiResponse(aiResponse);

        if (aiResponse.gatewaySource() == AIGateways.SPRINGAI
                && aiResponse instanceof SpringAIStreamResponse aiStreamResponse) {
            // Streaming: send paragraphs in real-time
            Integer[] replyToMessageId = {message.getMessageId()};
            int maxMessageLength = telegramProperties.getMaxMessageLength();
            ChatResponse chatResponse = AIUtils.processStreamingResponseByParagraphs(
                    aiStreamResponse.chatResponse(),
                    maxMessageLength,
                    s -> {
                        String htmlText = AIUtils.convertMarkdownToHtml(s);
                        ctx.getStreamingParagraphSender().accept(htmlText);
                        replyToMessageId[0] = null;
                    }
            );
            ctx.setUsefulResponseData(AIUtils.extractSpringAiUsefulData(chatResponse));
            AIUtils.extractText(chatResponse).ifPresent(ctx::setResponseText);
            extractError(chatResponse).ifPresent(ctx::setResponseError);
            ctx.setAlreadySentInStream(true);
        } else {
            // Non-streaming
            ctx.setUsefulResponseData(AIUtils.extractUsefulData(aiResponse));
            retrieveMessage(aiResponse).ifPresent(ctx::setResponseText);
            extractError(aiResponse).ifPresent(ctx::setResponseError);
            ctx.setAlreadySentInStream(false);
        }

        // Extract model name
        if (ctx.getUsefulResponseData() != null && ctx.getUsefulResponseData().containsKey("model")) {
            ctx.setResponseModel(String.valueOf(ctx.getUsefulResponseData().get("model")));
        }

        log.info("FSM extractResponseContext: gateway={}, model={}",
                aiResponse.gatewaySource(), ctx.getResponseModel());
    }

    private void handleGeneralException(MessageHandlerContext ctx, Exception e) {
        ctx.classifyAndSetError(e);
    }

    private String withTelegramBotIdentity(String assistantRoleContent) {
        String baseRole = assistantRoleContent != null ? assistantRoleContent.trim() : "";
        String normalizedBotUsername = telegramProperties.getNormalizedBotUsername();
        if (normalizedBotUsername == null) {
            return baseRole;
        }
        String identityClause = "You are bot with name " + normalizedBotUsername;
        if (baseRole.contains(identityClause)) {
            return baseRole;
        }
        if (baseRole.isEmpty()) {
            return identityClause;
        }
        String separator = baseRole.endsWith(".") ? " " : ". ";
        return baseRole + separator + identityClause;
    }

}
