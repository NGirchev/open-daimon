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
import io.github.ngirchev.opendaimon.telegram.service.ReplyImageAttachmentService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramAgentStreamRenderer;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserSessionService;
import io.github.ngirchev.opendaimon.telegram.service.UserModelPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    private static final String RAW_TOOL_PAYLOAD_ERROR = "raw_tool_payload_in_final_answer";
    private static final Set<String> TOOL_NAMES = Set.of("http_get", "http_post", "web_search", "fetch_url");
    private static final long STREAM_MIN_EDIT_INTERVAL_MS = 500L;
    private static final int FINAL_ANSWER_LOG_PREVIEW_LIMIT = 160;
    private static final int FINAL_ANSWER_PREVIEW_RESERVE_CHARS = 1;

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

            AgentRequest request = new AgentRequest(
                    command.userText(),
                    metadata.get(THREAD_KEY_FIELD),
                    metadata,
                    agentMaxIterations,
                    Set.of(),
                    strategy
            );

            // Stream agent events — sends intermediate status messages to Telegram
            // and captures the terminal event for final answer extraction.
            AgentStreamEvent lastEvent = agentExecutor.executeStream(request)
                    .doOnNext(event -> sendAgentEventToTelegram(ctx, event))
                    .blockLast();

            extractAgentResult(ctx, lastEvent);

            // Final answer fallback for executors that emit only terminal FINAL_ANSWER/MAX_ITERATIONS.
            // If FINAL_ANSWER_CHUNK stream already created a dedicated final message, do not duplicate.
            if (ctx.hasResponse()) {
                String answerText = ctx.getResponseText().orElse("");
                if (ctx.getAgentFinalAnswerMessageId() == null) {
                    log.info("FSM generateAgentResponse: sending fallback final answer, textLength={}, text='{}'",
                            answerText.length(), normalizeForLog(answerText));
                    Integer finalReplyToMessageId = ctx.getMessage() != null ? ctx.getMessage().getMessageId() : null;
                    sendTextByParagraphs(answerText, html -> messageSender.sendHtml(chatId, html, finalReplyToMessageId));
                } else {
                    log.info("FSM generateAgentResponse: final answer already streamed via message id={}",
                            ctx.getAgentFinalAnswerMessageId());
                }
                ctx.setAlreadySentInStream(true);
            } else {
                log.warn("FSM generateAgentResponse: no response text after extractAgentResult");
            }
        } catch (Exception e) {
            handleGeneralException(ctx, e);
        }
    }

    private void sendAgentEventToTelegram(MessageHandlerContext ctx, AgentStreamEvent event) {
        if (event.type() == AgentStreamEvent.EventType.FINAL_ANSWER_CHUNK) {
            // Chunks can arrive very frequently; keep them at DEBUG to avoid misleading INFO noise
            // when edit throttling is enabled.
            log.debug("FSM agentStreamEvent: type={}, iteration={}, contentLength={}, content='{}'",
                    event.type(), event.iteration(),
                    event.content() != null ? event.content().length() : 0,
                    normalizeForLog(event.content()));
        } else {
            log.info("FSM agentStreamEvent: type={}, iteration={}, contentLength={}, content='{}'",
                    event.type(), event.iteration(),
                    event.content() != null ? event.content().length() : 0,
                    normalizeForLog(event.content()));
        }
        // Capture model name from metadata event
        if (event.type() == AgentStreamEvent.EventType.METADATA && event.content() != null) {
            ctx.setResponseModel(event.content());
            return;
        }
        if (event.type() == AgentStreamEvent.EventType.TOOL_CALL && ctx.hasStreamedFinalAnswerChunks()) {
            log.warn("FSM agentStreamEvent: TOOL_CALL arrived after FINAL_ANSWER_CHUNK stream, "
                    + "rolling back tentative final answer");
            rollbackTentativeFinalAnswerToProgress(ctx, event.iteration(), ctx.getAgentFinalAnswerText());
        }
        if (event.type() == AgentStreamEvent.EventType.FINAL_ANSWER_CHUNK) {
            flushPendingProgressToTelegram(ctx, true);
            sendFinalAnswerChunkToTelegram(ctx, event);
            return;
        }
        boolean terminalFinalAnswer = event.type() == AgentStreamEvent.EventType.FINAL_ANSWER
                || event.type() == AgentStreamEvent.EventType.MAX_ITERATIONS;
        if (terminalFinalAnswer || event.type() == AgentStreamEvent.EventType.ERROR) {
            flushPendingProgressToTelegram(ctx, true);
            flushPendingFinalAnswerToTelegram(ctx, terminalFinalAnswer);
        }
        if (agentStreamRenderer == null) {
            return;
        }
        String html = agentStreamRenderer.render(event);
        MessageHandlerContext.AgentProgressUpdate progressUpdate = ctx.mergeAgentProgressEvent(
                event,
                html,
                telegramProperties.getMaxMessageLength()
        );
        if (!progressUpdate.changed()) {
            return;
        }
        scheduleProgressUpdate(ctx, progressUpdate, false);
    }

    private void scheduleProgressUpdate(MessageHandlerContext ctx,
                                        MessageHandlerContext.AgentProgressUpdate progressUpdate,
                                        boolean force) {
        if (progressUpdate.isEmpty()) {
            flushPendingProgressToTelegram(ctx, true);
            Integer progressMessageId = ctx.getAgentProgressMessageId();
            if (progressMessageId != null) {
                Long chatId = ctx.getCommand().telegramId();
                messageSender.deleteMessage(chatId, progressMessageId);
                ctx.setAgentProgressMessageId(null);
                log.info("FSM agentStreamEvent: deleted progress message id={}", progressMessageId);
            }
            ctx.clearAgentProgressPending();
            ctx.setAlreadySentInStream(true);
            return;
        }

        ctx.setAgentProgressPendingHtml(progressUpdate.html());
        if (progressUpdate.trimmedForOverflow()) {
            ctx.setAgentProgressPendingRequiresRotation(true);
        }
        deliverPendingProgressToTelegram(ctx, force);
    }

    private void flushPendingProgressToTelegram(MessageHandlerContext ctx, boolean force) {
        deliverPendingProgressToTelegram(ctx, force);
    }

    private void deliverPendingProgressToTelegram(MessageHandlerContext ctx, boolean force) {
        String pendingHtml = ctx.getAgentProgressPendingHtml();
        if (pendingHtml == null || pendingHtml.isBlank()) {
            return;
        }

        Integer progressMessageId = ctx.getAgentProgressMessageId();
        boolean rotateMessage = ctx.isAgentProgressPendingRequiresRotation() && progressMessageId != null;
        if (!force && progressMessageId != null && !rotateMessage && !shouldEditProgressMessage(ctx)) {
            return;
        }

        Long chatId = ctx.getCommand().telegramId();
        Integer replyToMessageId = ctx.getMessage() != null ? ctx.getMessage().getMessageId() : null;
        if (progressMessageId == null || rotateMessage) {
            Integer sentMessageId = messageSender.sendHtmlAndGetId(chatId, pendingHtml, replyToMessageId, true);
            if (sentMessageId == null) {
                return;
            }
            ctx.setAgentProgressMessageId(sentMessageId);
            if (rotateMessage) {
                log.info("FSM agentStreamEvent: rotated progress message, newId={}", sentMessageId);
            } else {
                log.info("FSM agentStreamEvent: created progress message id={}", sentMessageId);
            }
        } else {
            messageSender.editHtml(chatId, progressMessageId, pendingHtml, true);
            log.info("FSM agentStreamEvent: updated progress message id={}", progressMessageId);
        }

        ctx.clearAgentProgressPending();
        ctx.markAgentProgressDelivered();
        ctx.setAlreadySentInStream(true);
    }

    private void sendFinalAnswerChunkToTelegram(MessageHandlerContext ctx, AgentStreamEvent event) {
        String rawChunk = event.content();
        if (rawChunk == null || rawChunk.isBlank()) {
            return;
        }
        String tentativeAnswer = ctx.appendAgentFinalAnswerChunk(rawChunk);
        if (looksLikeToolCallPayload(tentativeAnswer)) {
            rollbackTentativeFinalAnswerToProgress(ctx, event.iteration(), tentativeAnswer);
            return;
        }
        publishFinalAnswerToTelegram(ctx, false, false);
    }

    private void rollbackTentativeFinalAnswerToProgress(MessageHandlerContext ctx,
                                                        int iteration,
                                                        String tentativeAnswer) {
        Long chatId = ctx.getCommand().telegramId();
        Integer finalAnswerMessageId = ctx.getAgentFinalAnswerMessageId();
        if (finalAnswerMessageId != null) {
            messageSender.deleteMessage(chatId, finalAnswerMessageId);
            log.warn("FSM agentStreamEvent: rolled back tentative final answer message id={}", finalAnswerMessageId);
        }

        String recoveredThinking = extractUserTextBeforeToolPayload(tentativeAnswer);
        ctx.resetAgentFinalAnswerStream();
        if (recoveredThinking.isBlank() || agentStreamRenderer == null) {
            return;
        }

        AgentStreamEvent thinkingEvent = AgentStreamEvent.thinking(recoveredThinking, iteration);
        String thinkingHtml = agentStreamRenderer.render(thinkingEvent);
        MessageHandlerContext.AgentProgressUpdate progressUpdate = ctx.mergeAgentProgressEvent(
                thinkingEvent,
                thinkingHtml,
                telegramProperties.getMaxMessageLength()
        );
        if (progressUpdate.changed()) {
            scheduleProgressUpdate(ctx, progressUpdate, true);
        }
    }

    private void flushPendingFinalAnswerToTelegram(MessageHandlerContext ctx,
                                                   boolean enablePreviewForFinalUpdate) {
        if (!ctx.hasStreamedFinalAnswerChunks()) {
            return;
        }
        if (ctx.getAgentFinalAnswerPendingChars() > 0) {
            publishFinalAnswerToTelegram(ctx, true, enablePreviewForFinalUpdate);
            return;
        }
        if (enablePreviewForFinalUpdate) {
            finalizeFinalAnswerPreview(ctx);
        }
    }

    private void publishFinalAnswerToTelegram(MessageHandlerContext ctx,
                                              boolean force,
                                              boolean enablePreviewForFinalUpdate) {
        if (!force && !shouldEditFinalAnswerMessage(ctx)) {
            return;
        }
        Long chatId = ctx.getCommand().telegramId();
        Integer replyToMessageId = ctx.getMessage() != null ? ctx.getMessage().getMessageId() : null;
        int maxLength = telegramProperties.getMaxMessageLength();

        while (ctx.getAgentFinalAnswerCurrentMessageStartOffset() < ctx.getAgentFinalAnswerText().length()) {
            int segmentStartOffset = ctx.getAgentFinalAnswerCurrentMessageStartOffset();
            String currentSegment = ctx.getAgentFinalAnswerText().substring(segmentStartOffset);
            int fitLength = findLargestPrefixThatFitsTelegramLimit(currentSegment, maxLength);
            if (fitLength <= 0) {
                log.warn("FSM agentStreamEvent: could not fit final answer segment into Telegram limit, segmentStart={}",
                        segmentStartOffset);
                return;
            }
            int initialFitLength = fitLength;
            boolean reservedTailForFinalPreview = false;
            if (!force && fitLength > FINAL_ANSWER_PREVIEW_RESERVE_CHARS) {
                int candidateDeliveredLength = segmentStartOffset + fitLength;
                if (candidateDeliveredLength >= ctx.getAgentFinalAnswerText().length()) {
                    fitLength -= FINAL_ANSWER_PREVIEW_RESERVE_CHARS;
                    reservedTailForFinalPreview = fitLength < initialFitLength;
                }
            }
            if (fitLength <= 0) {
                return;
            }

            String segmentToSend = currentSegment.substring(0, fitLength);
            String html = AIUtils.convertMarkdownToHtml(segmentToSend);
            String segmentPreview = previewForLog(segmentToSend, FINAL_ANSWER_LOG_PREVIEW_LIMIT);
            Integer finalAnswerMessageId = ctx.getAgentFinalAnswerMessageId();
            int deliveredLength = segmentStartOffset + fitLength;
            boolean isLastSegment = deliveredLength >= ctx.getAgentFinalAnswerText().length();
            boolean disableWebPagePreview = !(enablePreviewForFinalUpdate && isLastSegment);

            if (finalAnswerMessageId == null) {
                Integer sentMessageId = messageSender.sendHtmlAndGetId(
                        chatId,
                        html,
                        replyToMessageId,
                        disableWebPagePreview
                );
                if (sentMessageId == null) {
                    return;
                }
                ctx.setAgentFinalAnswerMessageId(sentMessageId);
                log.info("FSM agentStreamEvent: created final answer message id={}, preview='{}'",
                        sentMessageId, segmentPreview);
            } else {
                messageSender.editHtml(chatId, finalAnswerMessageId, html, disableWebPagePreview);
                log.info("FSM agentStreamEvent: updated final answer message id={}, preview='{}'",
                        finalAnswerMessageId, segmentPreview);
            }

            ctx.markAgentFinalAnswerDeliveredUpTo(deliveredLength);
            ctx.setAlreadySentInStream(true);

            if (deliveredLength >= ctx.getAgentFinalAnswerText().length()) {
                return;
            }
            if (reservedTailForFinalPreview) {
                // We intentionally keep a tiny tail for terminal FINAL_ANSWER/MAX_ITERATIONS update
                // so preview can be enabled on a real text change (no duplicate/no-op edit).
                return;
            }

            // Current message reached Telegram length limit — continue the remaining tail in a new message.
            ctx.setAgentFinalAnswerCurrentMessageStartOffset(deliveredLength);
            ctx.setAgentFinalAnswerMessageId(null);
            if (!force) {
                return;
            }
        }
    }

    private void finalizeFinalAnswerPreview(MessageHandlerContext ctx) {
        Integer finalAnswerMessageId = ctx.getAgentFinalAnswerMessageId();
        if (finalAnswerMessageId == null) {
            return;
        }

        int segmentStartOffset = ctx.getAgentFinalAnswerCurrentMessageStartOffset();
        if (segmentStartOffset >= ctx.getAgentFinalAnswerText().length()) {
            return;
        }

        String finalSegment = ctx.getAgentFinalAnswerText().substring(segmentStartOffset);
        if (finalSegment.isBlank()) {
            return;
        }

        Long chatId = ctx.getCommand().telegramId();
        String html = AIUtils.convertMarkdownToHtml(finalSegment);
        messageSender.editHtml(chatId, finalAnswerMessageId, html, false);
        ctx.markAgentFinalAnswerDeliveredUpTo(ctx.getAgentFinalAnswerText().length());
        log.info("FSM agentStreamEvent: finalized final answer message id={} with link preview enabled",
                finalAnswerMessageId);
    }

    private int findLargestPrefixThatFitsTelegramLimit(String text, int maxLength) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int low = 1;
        int high = text.length();
        int best = 0;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            String candidateHtml = AIUtils.convertMarkdownToHtml(text.substring(0, mid));
            if (candidateHtml.length() <= maxLength) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        if (best <= 0) {
            return 0;
        }
        return adjustChunkBoundaryForReadability(text, best);
    }

    private int adjustChunkBoundaryForReadability(String text, int bestFitLength) {
        if (bestFitLength >= text.length()) {
            return bestFitLength;
        }
        int threshold = Math.max(1, bestFitLength - 200);
        int paragraphBoundary = text.lastIndexOf("\n\n", bestFitLength - 1);
        int newlineBoundary = text.lastIndexOf('\n', bestFitLength - 1);
        int spaceBoundary = text.lastIndexOf(' ', bestFitLength - 1);

        int candidate = Math.max(
                Math.max(paragraphBoundary >= 0 ? paragraphBoundary + 2 : 0,
                        newlineBoundary >= 0 ? newlineBoundary + 1 : 0),
                spaceBoundary >= 0 ? spaceBoundary + 1 : 0
        );

        if (candidate >= threshold) {
            return candidate;
        }
        return bestFitLength;
    }

    private static boolean shouldEditFinalAnswerMessage(MessageHandlerContext ctx) {
        if (ctx.getAgentFinalAnswerPendingChars() <= 0) {
            return false;
        }
        long elapsedSinceLastEditMs = System.currentTimeMillis() - ctx.getAgentFinalAnswerLastDeliveryAtMillis();
        return elapsedSinceLastEditMs >= STREAM_MIN_EDIT_INTERVAL_MS;
    }

    private static boolean shouldEditProgressMessage(MessageHandlerContext ctx) {
        long elapsedSinceLastEditMs = System.currentTimeMillis() - ctx.getAgentProgressLastDeliveryAtMillis();
        return elapsedSinceLastEditMs >= STREAM_MIN_EDIT_INTERVAL_MS;
    }

    private void extractAgentResult(MessageHandlerContext ctx, AgentStreamEvent lastEvent) {
        if (lastEvent == null) {
            ctx.setErrorType(MessageHandlerErrorType.EMPTY_RESPONSE);
            return;
        }

        log.info("FSM generateAgentResponse: terminalEvent={}, iteration={}, content='{}'",
                lastEvent.type(), lastEvent.iteration(), normalizeForLog(lastEvent.content()));

        if ((lastEvent.type() == AgentStreamEvent.EventType.FINAL_ANSWER
                || lastEvent.type() == AgentStreamEvent.EventType.MAX_ITERATIONS)
                && lastEvent.content() != null) {
            if (looksLikeToolCallPayload(lastEvent.content())) {
                String recoveredUserText = extractUserTextBeforeToolPayload(lastEvent.content());
                if (!recoveredUserText.isBlank()) {
                    log.warn("FSM generateAgentResponse: {} contains mixed payload, recovered user text, content='{}'",
                            lastEvent.type(), normalizeForLog(lastEvent.content()));
                    ctx.setResponseText(recoveredUserText);
                    return;
                }
                if (ctx.hasStreamedFinalAnswerChunks()) {
                    ctx.setResponseText(ctx.getAgentFinalAnswerText().trim());
                    return;
                }
                log.warn("FSM generateAgentResponse: {} contains raw tool payload without recoverable text, content='{}'",
                        lastEvent.type(), normalizeForLog(lastEvent.content()));
                ctx.setResponseError(RAW_TOOL_PAYLOAD_ERROR);
                ctx.setErrorType(MessageHandlerErrorType.EMPTY_RESPONSE);
                return;
            }
            ctx.setResponseText(lastEvent.content());
        } else if (lastEvent.type() == AgentStreamEvent.EventType.ERROR) {
            ctx.setErrorType(MessageHandlerErrorType.GENERAL);
            ctx.setException(new RuntimeException(lastEvent.content()));
        } else if (ctx.hasStreamedFinalAnswerChunks()) {
            ctx.setResponseText(ctx.getAgentFinalAnswerText().trim());
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

    private static boolean looksLikeToolCallPayload(String text) {
        return findFirstToolPayloadIndex(text) >= 0;
    }

    private static String extractUserTextBeforeToolPayload(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int markerIndex = findFirstToolPayloadIndex(text);
        String candidate = markerIndex >= 0 ? text.substring(0, markerIndex) : text;
        return candidate.trim();
    }

    private static int findFirstToolPayloadIndex(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        int firstIndex = Integer.MAX_VALUE;
        String lowered = text.toLowerCase(Locale.ROOT);
        String[] markers = {
                "<tool_call",
                "</tool_call>",
                "<arg_key>",
                "</arg_key>",
                "<arg_value>",
                "</arg_value>",
                "<tool_name>",
                "</tool_name>"
        };
        for (String marker : markers) {
            int markerIndex = lowered.indexOf(marker);
            if (markerIndex >= 0 && markerIndex < firstIndex) {
                firstIndex = markerIndex;
            }
        }
        int standaloneToolLineIndex = findFirstStandaloneToolLineIndex(lowered);
        if (standaloneToolLineIndex >= 0 && standaloneToolLineIndex < firstIndex) {
            firstIndex = standaloneToolLineIndex;
        }
        return firstIndex == Integer.MAX_VALUE ? -1 : firstIndex;
    }

    private static int findFirstStandaloneToolLineIndex(String loweredText) {
        int offset = 0;
        for (String rawLine : loweredText.split("\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (TOOL_NAMES.contains(line.trim())) {
                return offset;
            }
            offset += rawLine.length() + 1;
        }
        return -1;
    }

    private static String normalizeForLog(String text) {
        if (text == null) {
            return "null";
        }
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String previewForLog(String text, int maxLength) {
        String normalized = normalizeForLog(text);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

}
