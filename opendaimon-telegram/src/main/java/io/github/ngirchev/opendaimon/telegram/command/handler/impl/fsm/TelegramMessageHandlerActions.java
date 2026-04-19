package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentStrategy;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.ai.AIGateways;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
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
import io.github.ngirchev.opendaimon.telegram.service.TelegramAgentStreamRenderer;
import io.github.ngirchev.opendaimon.telegram.service.TelegramHtmlEscaper;
import io.github.ngirchev.opendaimon.telegram.service.TelegramProgressBatcher;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserSessionService;
import io.github.ngirchev.opendaimon.telegram.service.ToolLabels;
import io.github.ngirchev.opendaimon.telegram.service.UserModelPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.telegram.telegrambots.meta.api.objects.Message;

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

    /** Marker appended to the status message when the tentative answer bubble opens. */
    private static final String STATUS_ANSWERING_LINE = "ℹ️ Answering…";

    /** Marker appended to the status message when MAX_ITERATIONS terminates the loop. */
    private static final String STATUS_MAX_ITER_LINE = "⚠️ reached iteration limit";

    /**
     * Escaped HTML placed into the tentative answer bubble on delete failure, instead of
     * deleting it. Standalone {@code <i>…</i>} is safe in parse_mode=HTML.
     */
    private static final String ROLLBACK_FALLBACK_HTML = "<i>(folded into reasoning)</i>";

    /**
     * Tool-call markers that may leak through the upstream
     * {@code io.github.ngirchev.opendaimon.ai.springai.agent.StreamingAnswerFilter} when
     * the provider emits a pseudo-XML tool-call variant the filter doesn't recognize
     * (e.g. {@code <arg_key>}/{@code <arg_value>} from some Qwen/Ollama flavors).
     *
     * <p>Stored in the escaped form because the tentative-answer buffer always holds
     * HTML-escaped content (see {@link TelegramHtmlEscaper}), and so does the raw
     * PARTIAL_ANSWER chunk after we escape it.
     *
     * <p>Per spec (§"Final answer transition", step 3 and the Russian draft point 9):
     * if any of these markers appears in what we tentatively treated as the answer,
     * the output was actually reasoning with an embedded tool call; the tentative
     * bubble must be deleted and its prose folded back into the status transcript.
     */
    private static final String[] ESCAPED_TOOL_MARKERS = {
            "&lt;tool_call&gt;", "&lt;/tool_call&gt;",
            "&lt;tool&gt;", "&lt;/tool&gt;",
            "&lt;arg_key&gt;", "&lt;/arg_key&gt;",
            "&lt;arg_value&gt;", "&lt;/arg_value&gt;"
    };

    /** Longest escaped marker length — bounds the overlap when resuming an incremental scan. */
    private static final int MAX_ESCAPED_TOOL_MARKER_LEN = maxLength(ESCAPED_TOOL_MARKERS);

    private static int maxLength(String[] arr) {
        int max = 0;
        for (String s : arr) {
            if (s.length() > max) max = s.length();
        }
        return max;
    }

    private final TelegramUserService telegramUserService;
    private final TelegramUserSessionService telegramUserSessionService;
    private final TelegramMessageService telegramMessageService;
    private final AIGatewayRegistry aiGatewayRegistry;
    private final OpenDaimonMessageService messageService;
    private final AIRequestPipeline aiRequestPipeline;
    private final TelegramProperties telegramProperties;
    private final UserModelPreferenceService userModelPreferenceService;
    private final PersistentKeyboardService persistentKeyboardService;
    private final ReplyImageAttachmentService replyImageAttachmentService;

    /** Callback for sending messages — provided by the handler (wraps TelegramBot API). */
    private final TelegramMessageSender messageSender;

    /** Agent executor — null when {@code open-daimon.agent.enabled=false}. */
    private final AgentExecutor agentExecutor;
    /** Renderer for agent stream events — null when {@code agentExecutor} is null. */
    private final TelegramAgentStreamRenderer agentStreamRenderer;
    /** Agent max iterations — only used when {@code agentExecutor} is non-null. */
    private final int agentMaxIterations;

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
        if (telegramUser.getLanguageCode() != null) {
            metadata.put(LANGUAGE_CODE_FIELD, telegramUser.getLanguageCode());
        }
        userModelPreferenceService.getPreferredModel(telegramUser.getId())
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

            // Agent mode uses AgentExecutor, not AIGateway — skip gateway lookup
            if (agentExecutor == null) {
                AIGateway aiGateway = aiGatewayRegistry.getSupportedAiGateways(aiCommand)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException(AIUtils.NO_SUPPORTED_AI_GATEWAY));
                ctx.setAiGateway(aiGateway);
            }

            log.debug("FSM createCommand: capabilities={}, agentMode={}", aiCommand.modelCapabilities(), agentExecutor != null);
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
        if (agentExecutor != null) {
            generateAgentResponse(ctx);
        } else {
            generateGatewayResponse(ctx);
        }
    }

    private void generateAgentResponse(MessageHandlerContext ctx) {
        TelegramCommand command = ctx.getCommand();
        Map<String, String> metadata = ctx.getMetadata();
        Long chatId = command.telegramId();

        try {
            Set<ModelCapabilities> capabilities = ctx.getModelCapabilities();
            boolean hasToolAccess = capabilities != null
                    && (capabilities.contains(ModelCapabilities.WEB)
                        || capabilities.contains(ModelCapabilities.AUTO));
            AgentStrategy strategy = hasToolAccess ? AgentStrategy.AUTO : AgentStrategy.SIMPLE;
            log.info("FSM generateAgentResponse: capabilities={}, strategy={}", capabilities, strategy);

            ensureStatusMessage(ctx);

            AgentRequest request = new AgentRequest(
                    command.userText(),
                    metadata.get(THREAD_KEY_FIELD),
                    metadata,
                    agentMaxIterations,
                    Set.of(),
                    strategy
            );

            // Stream agent events — two-message UX:
            //   status  ← iteration log (thinking / tool-call / observation / error)
            //   answer  ← tentative final answer bubble (may be rolled back on TOOL_CALL)
            AgentStreamEvent lastEvent = agentExecutor.executeStream(request)
                    .doOnNext(event -> handleAgentStreamEvent(ctx, event))
                    .onErrorResume(err -> {
                        log.warn("FSM agentStreamEvent: stream errored — finalizing buffers", err);
                        handleStreamError(ctx, err);
                        return reactor.core.publisher.Flux.empty();
                    })
                    .blockLast();

            finalizeAfterStream(ctx, lastEvent);

            extractAgentResult(ctx, lastEvent);

            if (ctx.hasResponse()) {
                String answerText = ctx.getResponseText().orElse("");
                if (ctx.isTentativeAnswerActive()) {
                    // Drain-replace-drain: the streamed tentative-answer buffer holds raw model
                    // output, but `answerText` is the sanitized final text (e.g. dead links
                    // stripped by UrlLivenessChecker upstream). First drain any pending rotation
                    // of the streamed buffer, then replace the buffer's content with the
                    // sanitized text so the final bubble edit renders the clean version.
                    forceFinalAnswerEdit(ctx);
                    if (!answerText.isEmpty()) {
                        StringBuilder buf = ctx.getTentativeAnswerBuffer();
                        buf.setLength(0);
                        buf.append(TelegramHtmlEscaper.escape(answerText));
                        forceFinalAnswerEdit(ctx);
                    }
                    ctx.setTentativeAnswerActive(false);
                    log.info("FSM generateAgentResponse: final answer streamed via tentative bubble, textLength={}",
                            answerText.length());
                } else if (ctx.getTentativeAnswerMessageId() != null
                        && ctx.getTentativeAnswerBuffer().length() > 0) {
                    // Tentative bubble exists but was never promoted to active (shouldn't happen)
                    // — still force a final edit so nothing is lost.
                    forceFinalAnswerEdit(ctx);
                } else if (!answerText.isEmpty()) {
                    // No PARTIAL_ANSWER chunks ever opened a tentative bubble — send the
                    // final answer now as a fresh, paragraph-split message.
                    log.info("FSM generateAgentResponse: sending final answer as fresh message, textLength={}",
                            answerText.length());
                    sendTextByParagraphs(answerText, html -> messageSender.sendHtml(chatId, html, null));
                }
                ctx.setAlreadySentInStream(true);
            } else {
                log.warn("FSM generateAgentResponse: no response text after extractAgentResult");
            }
        } catch (Exception e) {
            handleGeneralException(ctx, e);
        }
    }

    /**
     * Dispatches a single agent stream event: updates iteration bookkeeping, rebuilds state
     * from the renderer's {@link RenderedUpdate} description, and orchestrates the
     * tentative-answer bubble lifecycle directly for {@code PARTIAL_ANSWER} /
     * {@code FINAL_ANSWER} / {@code MAX_ITERATIONS} (which touch message IDs and throttle).
     */
    private void handleAgentStreamEvent(MessageHandlerContext ctx, AgentStreamEvent event) {
        // PARTIAL_ANSWER fires per-token (1–8 bytes) and would dominate INFO logs,
        // hiding structural events like TOOL_CALL/OBSERVATION/FINAL_ANSWER. Demote it
        // to DEBUG so upstream-stream gaps become visible as silence in INFO.
        if (event.type() == AgentStreamEvent.EventType.PARTIAL_ANSWER) {
            log.debug("FSM agentStreamEvent: type={}, iteration={}, contentLength={}",
                    event.type(), event.iteration(),
                    event.content() != null ? event.content().length() : 0);
        } else {
            log.info("FSM agentStreamEvent: type={}, iteration={}, contentLength={}",
                    event.type(), event.iteration(),
                    event.content() != null ? event.content().length() : 0);
        }

        // Capture model name — side state, not transcript.
        if (event.type() == AgentStreamEvent.EventType.METADATA && event.content() != null) {
            ctx.setResponseModel(event.content());
            return;
        }

        if (agentStreamRenderer == null) {
            return;
        }

        if (event.type() == AgentStreamEvent.EventType.PARTIAL_ANSWER) {
            handlePartialAnswer(ctx, event);
            return;
        }
        if (event.type() == AgentStreamEvent.EventType.FINAL_ANSWER) {
            // Final answer payload becomes responseText in extractAgentResult; nothing to render.
            return;
        }
        if (event.type() == AgentStreamEvent.EventType.MAX_ITERATIONS) {
            appendToStatusBuffer(ctx, "\n\n" + STATUS_MAX_ITER_LINE, /*forceFlush=*/ true);
            return;
        }

        // Update iteration bookkeeping BEFORE asking the renderer — it reads
        // ctx.getCurrentIteration() to decide whether a null-content THINKING is an
        // iteration-rollover marker.
        RenderedUpdate update = agentStreamRenderer.render(event, ctx);
        applyUpdate(ctx, update);

        if (event.type() == AgentStreamEvent.EventType.THINKING
                && event.iteration() != ctx.getCurrentIteration()) {
            ctx.setCurrentIteration(event.iteration());
            ctx.setToolCallSeenThisIteration(false);
        }
        if (event.type() == AgentStreamEvent.EventType.TOOL_CALL) {
            ctx.setToolCallSeenThisIteration(true);
            // A TOOL_CALL arriving at all — active bubble or not — retroactively proves any
            // PARTIAL_ANSWER chunks accumulated in this iteration were pre-tool reasoning,
            // not a final answer. If the bubble had already been promoted, RollbackAndAppendToolCall
            // clears the buffer via resetTentativeAnswer(). If it hadn't (no \n\n boundary was
            // ever reached), the buffer would otherwise leak into the next iteration and the
            // eventual real answer would be rendered with the stale reasoning prepended.
            // Observed in production with models that emit structured tool calls together
            // with prose in the same stream (e.g. z-ai/glm-4.5v).
            if (!ctx.isTentativeAnswerActive()) {
                ctx.getTentativeAnswerBuffer().setLength(0);
            }
        }
    }

    /**
     * PARTIAL_ANSWER chunks flow into the tentative-answer buffer. While in
     * {@code STATUS_ONLY} mode, the tail of the buffer is shown inline as the reasoning
     * overlay on the status message and the orchestrator immediately promotes the
     * answer into a separate Telegram bubble — unless a tool call has already been
     * seen in this iteration. Rollback triggers (tool-marker text scan and TOOL_CALL
     * event) remove the bubble if the content later turns out to be pre-tool reasoning.
     */
    private void handlePartialAnswer(MessageHandlerContext ctx, AgentStreamEvent event) {
        String chunk = event.content();
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        String escaped = TelegramHtmlEscaper.escape(chunk);
        StringBuilder buf = ctx.getTentativeAnswerBuffer();
        buf.append(escaped);

        // Spec "Final answer transition" step 3 and Russian draft point 9: if the
        // streamed content contains a tool-call marker the upstream filter missed
        // (e.g. <arg_key>/<arg_value> from Qwen-style provider output), the text is
        // reasoning with an embedded tool call, NOT a final answer. Rollback any
        // open tentative bubble and suppress promotion for the rest of the iteration.
        // Once the flag is set, the iteration already suppresses promotion — skip the
        // scan so we don't re-enter rollback on every subsequent chunk.
        if (!ctx.isToolCallSeenThisIteration()
                && containsToolMarker(buf, ctx.getToolMarkerScanOffset())) {
            handleEmbeddedToolMarker(ctx, buf);
            return;
        }
        ctx.setToolMarkerScanOffset(buf.length());

        if (ctx.getAgentRenderMode() == MessageHandlerContext.AgentRenderMode.STATUS_ONLY) {
            // Show the streaming tail inline on the status message as reasoning overlay.
            replaceTrailingThinkingLineWithEscaped(ctx, tailAsPlainOverlay(buf), /*forceFlush=*/ false);
            if (!ctx.isToolCallSeenThisIteration()) {
                promoteTentativeAnswer(ctx);
            }
            return;
        }

        // TENTATIVE_ANSWER mode — edit the dedicated bubble with the full (rotated) buffer.
        editTentativeAnswer(ctx, /*forceFlush=*/ false);
    }

    /**
     * Scans the tentative-answer buffer for escaped tool-call markers. Returns true the
     * first time a marker is found; callers should rollback and set
     * {@link MessageHandlerContext#setToolCallSeenThisIteration(boolean)} so this scan
     * short-circuits (via the {@code toolCallSeen} flag) for the rest of the iteration.
     */
    /**
     * Incremental marker scan. Starts at {@code max(0, prevScannedOffset - MAX_MARKER_LEN + 1)}
     * so a marker that straddles the boundary between the previously-scanned prefix and the
     * newly-appended chunk is still detected, while never re-scanning bytes further back than
     * necessary.
     */
    private static boolean containsToolMarker(StringBuilder buf, int prevScannedOffset) {
        int start = Math.max(0, prevScannedOffset - MAX_ESCAPED_TOOL_MARKER_LEN + 1);
        for (String marker : ESCAPED_TOOL_MARKERS) {
            if (buf.indexOf(marker, start) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tool marker detected inside what we were streaming as the final answer.
     * Per spec: if the tentative bubble is already open, delete it and fold its prose
     * back into status as reasoning; otherwise, just suppress promotion for this
     * iteration and keep rendering the overlay tail. The subsequent TOOL_CALL event
     * from the agent loop will render the canonical tool block on the status message.
     */
    private void handleEmbeddedToolMarker(MessageHandlerContext ctx, StringBuilder buf) {
        ctx.setToolCallSeenThisIteration(true);
        if (ctx.isTentativeAnswerActive()) {
            Long chatId = ctx.getCommand().telegramId();
            Integer id = ctx.getTentativeAnswerMessageId();
            String foldedProse = buf.toString();
            boolean rollbackVisual = false;
            if (id != null) {
                boolean deleted = messageSender.deleteMessage(chatId, id);
                rollbackVisual = deleted;
                if (!deleted) {
                    try {
                        messageSender.editHtml(chatId, id, ROLLBACK_FALLBACK_HTML, true);
                        rollbackVisual = true;
                    } catch (RuntimeException ex) {
                        log.error("FSM agentStream: marker-rollback failed to both delete and edit bubble id={} — "
                                + "orphaned partial answer will remain visible; reasoning preserved as status overlay",
                                id, ex);
                    }
                }
            }
            String foldedOverlay = "<i>" + collapseToSingleLine(foldedProse) + "</i>";
            replaceTrailingThinkingLineWithEscaped(ctx, foldedOverlay, /*forceFlush=*/ true);
            ctx.resetTentativeAnswer();
            log.info("FSM agentStream: tool marker detected in tentative answer, bubble id={} rolled back (visual={})",
                    id, rollbackVisual);
        } else {
            // Still in STATUS_ONLY — show the collapsed reasoning tail, don't promote.
            replaceTrailingThinkingLineWithEscaped(ctx, tailAsPlainOverlay(buf), /*forceFlush=*/ false);
            log.debug("FSM agentStream: tool marker detected in STATUS_ONLY tail, promotion suppressed");
        }
    }

    /**
     * Reads the last few hundred chars of the tentative buffer and wraps them in
     * {@code <i>…</i>} for display as the reasoning overlay line on the status message.
     * Capped so the status message doesn't balloon; the full text lives in the buffer.
     */
    private String tailAsPlainOverlay(StringBuilder buf) {
        int tailLimit = 400;
        int start = Math.max(0, buf.length() - tailLimit);
        String tail = buf.substring(start);
        return "<i>" + collapseToSingleLine(tail) + "</i>";
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

    private void applyUpdate(MessageHandlerContext ctx, RenderedUpdate update) {
        switch (update) {
            case RenderedUpdate.ReplaceTrailingThinkingLine r ->
                    replaceTrailingThinkingLineWithEscaped(ctx,
                            "<i>" + collapseToSingleLine(TelegramHtmlEscaper.escape(r.reasoning())) + "</i>",
                            /*forceFlush=*/ false);
            case RenderedUpdate.AppendFreshThinking ignored -> {
                appendToStatusBuffer(ctx, "\n\n" + STATUS_THINKING_LINE, /*forceFlush=*/ false);
            }
            case RenderedUpdate.AppendToolCall tc ->
                    appendToolCallBlock(ctx, tc.toolName(), tc.args());
            case RenderedUpdate.AppendObservation obs ->
                    appendObservationMarker(ctx, obs.kind(), obs.errorSummary());
            case RenderedUpdate.AppendErrorToStatus err ->
                    appendToStatusBuffer(ctx,
                            "\n\n❌ Error: " + TelegramHtmlEscaper.escape(err.message()),
                            /*forceFlush=*/ true);
            case RenderedUpdate.RollbackAndAppendToolCall rb ->
                    rollbackAndAppendToolCall(ctx, rb.toolName(), rb.args(), rb.foldedProse());
            case RenderedUpdate.NoOp ignored -> { /* no-op */ }
        }
    }

    // --- Status message helpers ---

    /**
     * Sends the initial {@code 💭 Thinking...} status message (once per agent run) and
     * seeds {@link MessageHandlerContext#getStatusBuffer()} with its pre-escaped HTML so
     * subsequent edits just overwrite the whole buffer. If the send fails the buffer
     * still carries the text and later edit attempts short-circuit.
     */
    private void ensureStatusMessage(MessageHandlerContext ctx) {
        if (ctx.getStatusMessageId() != null) {
            return;
        }
        Long chatId = ctx.getCommand().telegramId();
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

    private void appendToolCallBlock(MessageHandlerContext ctx, String toolName, String args) {
        String label = ToolLabels.label(toolName);
        String escapedArgs = args == null || args.isBlank()
                ? ""
                : TelegramHtmlEscaper.escape(ToolLabels.truncateArg(args));
        String blockBody = escapedArgs.isEmpty()
                ? "🔧 <b>Tool:</b> " + label + "\n<b>Query:</b> …"
                : "🔧 <b>Tool:</b> " + label + "\n<b>Query:</b> " + escapedArgs;
        // Per spec §"Iteration flow": the tool call replaces the trailing thinking/reasoning
        // line — visual chronology "thinking → tool call → result" comes from TIME, not space.
        // The pacedForceFlushStatus call below guarantees the previous edit (placeholder or
        // reasoning overlay) has been visible on screen for at least one throttle window
        // before the tool-call block overwrites it. Without that pacing, a model that
        // emits a structured tool call without preceding text would replace "💭 Thinking..."
        // within the same tick and the user would never see the thinking state at all.
        StringBuilder buf = ctx.getStatusBuffer();
        int lastBoundary = buf.lastIndexOf("\n\n");
        int cut = lastBoundary >= 0 ? lastBoundary + 2 : 0;
        buf.setLength(cut);
        buf.append(blockBody);
        rotateStatusIfNeeded(ctx);
        pacedForceFlushStatus(ctx);
    }

    private void appendObservationMarker(MessageHandlerContext ctx,
                                         RenderedUpdate.ObservationKind kind,
                                         String escapedErrorSummary) {
        String body = switch (kind) {
            case RESULT -> "📋 Tool result received";
            case EMPTY -> "📋 No result";
            case FAILED -> "⚠️ Tool failed: " + TelegramHtmlEscaper.escape(escapedErrorSummary);
        };
        ctx.getStatusBuffer().append("\n<blockquote>").append(body).append("</blockquote>");
        rotateStatusIfNeeded(ctx);
        pacedForceFlushStatus(ctx);
    }

    /**
     * Sleeps until at least one throttle window has elapsed since the last status edit, then
     * pushes the current buffer to Telegram. Used for transitions between iteration phases
     * (thinking → tool call → observation) to give the user time to visually register each
     * state — the throttle interval ({@code open-daimon.telegram.agent-stream-edit-min-interval-ms})
     * doubles as the minimum paced gap between phase-transition edits.
     *
     * <p>When {@code throttleMs == 0} (test fixtures typically set this to disable throttling),
     * the sleep short-circuits and the helper degrades to a plain force flush.
     */
    private void pacedForceFlushStatus(MessageHandlerContext ctx) {
        long throttleMs = telegramProperties.getAgentStreamEditMinIntervalMs();
        long sinceLast = System.currentTimeMillis() - ctx.getLastStatusEditAtMs();
        if (throttleMs > 0 && sinceLast < throttleMs) {
            try {
                Thread.sleep(throttleMs - sinceLast);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        editStatusThrottled(ctx, /*forceFlush=*/ true);
    }

    // --- Tentative answer helpers ---

    /** Opens a separate answer bubble, switches render mode, and drops an "Answering…" marker on status. */
    private void promoteTentativeAnswer(MessageHandlerContext ctx) {
        Long chatId = ctx.getCommand().telegramId();
        String html = renderTentativeBuffer(ctx);
        Integer replyTo = ctx.getMessage() != null ? ctx.getMessage().getMessageId() : null;
        Integer sentId = messageSender.sendHtmlAndGetId(chatId, html, replyTo, true);
        if (sentId == null) {
            log.warn("FSM agentStream: tentative answer bubble send failed — staying in STATUS_ONLY");
            return;
        }
        ctx.setTentativeAnswerMessageId(sentId);
        ctx.setTentativeAnswerActive(true);
        ctx.setAgentRenderMode(MessageHandlerContext.AgentRenderMode.TENTATIVE_ANSWER);
        ctx.markAnswerEdited();
        ctx.setAlreadySentInStream(true);
        replaceTrailingThinkingLineWithEscaped(ctx, STATUS_ANSWERING_LINE, /*forceFlush=*/ true);
        log.info("FSM agentStream: tentative answer bubble opened id={}", sentId);
    }

    /**
     * The tentative-answer buffer holds HTML-escaped fragments (per spec §516) but the model
     * output still carries raw Markdown ({@code **bold**}, backticks, etc.). Convert those
     * Markdown tokens to Telegram HTML tags here so users see formatting in the answer bubble
     * — cannot use {@link AIUtils#convertMarkdownToHtml(String)} because it would re-escape
     * the already-escaped content.
     */
    private static String renderTentativeBuffer(MessageHandlerContext ctx) {
        return AIUtils.convertEscapedMarkdownToHtml(ctx.getTentativeAnswerBuffer().toString());
    }

    private void editTentativeAnswer(MessageHandlerContext ctx, boolean forceFlush) {
        Integer id = ctx.getTentativeAnswerMessageId();
        if (id == null) {
            return;
        }
        long debounceMs = telegramProperties.getAgentStreamEditMinIntervalMs();
        if (!TelegramProgressBatcher.shouldFlush(
                ctx.getLastAnswerEditAtMs(), System.currentTimeMillis(), debounceMs, forceFlush)) {
            return;
        }
        // Enable link previews only on the terminal edit (forceFlush). During streaming the
        // URL is still being typed character-by-character — a live preview would either fail
        // to resolve or flicker on every edit.
        boolean disablePreview = !forceFlush;
        Long chatId = ctx.getCommand().telegramId();
        TelegramProgressBatcher.selectContentToFlush(ctx.getTentativeAnswerBuffer(),
                        telegramProperties.getMaxMessageLength())
                .ifPresent(head -> {
                    // Finalize the current answer bubble with the head and open a fresh
                    // bubble for the tail — prior bubble id is dropped.
                    messageSender.editHtml(chatId, id,
                            AIUtils.convertEscapedMarkdownToHtml(head), disablePreview);
                    Integer next = messageSender.sendHtmlAndGetId(chatId,
                            renderTentativeBuffer(ctx), null, disablePreview);
                    if (next != null) {
                        ctx.setTentativeAnswerMessageId(next);
                    }
                });
        // After rotation the tail may be empty or whitespace-only; Telegram rejects an
        // editMessageText with empty body ("Bad Request: text must be non-empty"), so skip
        // the edit and leave the debounce timer untouched until real content arrives.
        String currentHtml = renderTentativeBuffer(ctx);
        if (!currentHtml.isBlank()) {
            messageSender.editHtml(chatId, ctx.getTentativeAnswerMessageId(),
                    currentHtml, disablePreview);
            ctx.markAnswerEdited();
        }
    }

    private void forceFinalAnswerEdit(MessageHandlerContext ctx) {
        editTentativeAnswer(ctx, /*forceFlush=*/ true);
    }

    /**
     * Tentative answer turned out to be reasoning: delete the bubble (or, on failure, edit
     * it to a graceful fallback so the user isn't left with stale content), fold the prose
     * into the status transcript as a reasoning line, and append a tool-call block.
     */
    private void rollbackAndAppendToolCall(MessageHandlerContext ctx, String toolName,
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
        appendToolCallBlock(ctx, toolName, args);
    }

    // --- Stream-terminal helpers ---

    /**
     * Stream finished normally — if nothing new was rendered after the last throttled edit,
     * flush both status and tentative-answer buffers once.
     */
    private void finalizeAfterStream(MessageHandlerContext ctx, AgentStreamEvent lastEvent) {
        editStatusThrottled(ctx, /*forceFlush=*/ true);
        if (ctx.getTentativeAnswerMessageId() != null
                && ctx.getTentativeAnswerBuffer().length() > 0) {
            forceFinalAnswerEdit(ctx);
        }
    }

    /**
     * Stream errored — make sure any tentative answer bubble is finalized with its current
     * buffer (the user shouldn't see an abandoned, partially-written answer) and append the
     * error marker to the status transcript.
     */
    private void handleStreamError(MessageHandlerContext ctx, Throwable err) {
        if (ctx.isTentativeAnswerActive()) {
            forceFinalAnswerEdit(ctx);
            ctx.setTentativeAnswerActive(false);
        }
        String msg = err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName();
        appendToStatusBuffer(ctx, "\n\n❌ Error: " + TelegramHtmlEscaper.escape(msg), /*forceFlush=*/ true);
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
                // Guardrail recovery: clear preference, rebuild command, retry
                log.warn("FSM generateResponse: guardrail error for model={}, retrying",
                        e.getModelId());
                messageSender.sendNotification(command.telegramId(),
                        "common.error.model.guardrail", command.languageCode(), e.getModelId());
                userModelPreferenceService.clearPreference(ctx.getTelegramUser().getId());
                // Clear preferred model from metadata — gateway will select a different model.
                // Reuse the existing aiCommand (preserves augmented query + processed attachments).
                aiCommand.metadata().remove(PREFERRED_MODEL_ID_FIELD);
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
