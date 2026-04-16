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

            // Stream agent events — edits a single status message in Telegram
            // and captures the terminal event for final answer extraction.
            AgentStreamEvent lastEvent = agentExecutor.executeStream(request)
                    .doOnNext(event -> handleAgentStreamEvent(ctx, event))
                    .blockLast();

            extractAgentResult(ctx, lastEvent);

            // Final answer is sent as a separate Telegram message (not as an edit
            // of the progress message).
            if (ctx.hasResponse()) {
                String answerText = ctx.getResponseText().orElse("");
                log.info("FSM generateAgentResponse: sending final answer, textLength={}", answerText.length());
                ctx.clearNextReplyToMessageId();
                sendTextByParagraphs(answerText, html -> messageSender.sendHtml(chatId, html, null));
                ctx.setAlreadySentInStream(true);
            } else {
                log.warn("FSM generateAgentResponse: no response text after extractAgentResult");
            }
        } catch (Exception e) {
            handleGeneralException(ctx, e);
        }
    }

    /**
     * Handles a single agent stream event by accumulating rendered HTML into one
     * Telegram status message. The first renderable event sends a new message;
     * subsequent events edit it. Link previews are disabled for status messages.
     */
    private void handleAgentStreamEvent(MessageHandlerContext ctx, AgentStreamEvent event) {
        log.info("FSM agentStreamEvent: type={}, iteration={}, contentLength={}",
                event.type(), event.iteration(),
                event.content() != null ? event.content().length() : 0);

        // Capture model name from metadata event
        if (event.type() == AgentStreamEvent.EventType.METADATA && event.content() != null) {
            ctx.setResponseModel(event.content());
            return;
        }
        if (agentStreamRenderer == null) {
            return;
        }

        String html = agentStreamRenderer.render(event);
        if (html == null) {
            return;
        }

        Long chatId = ctx.getCommand().telegramId();
        boolean isThinking = event.type() == AgentStreamEvent.EventType.THINKING;
        String progressHtml = ctx.appendAgentProgressChunk(html, telegramProperties.getMaxMessageLength(), isThinking);
        Integer progressMessageId = ctx.getAgentProgressMessageId();

        if (progressMessageId == null) {
            Integer sentMessageId = messageSender.sendHtmlAndGetId(
                    chatId, progressHtml, ctx.consumeNextReplyToMessageId(), true);
            if (sentMessageId != null) {
                ctx.setAgentProgressMessageId(sentMessageId);
                log.info("FSM agentStreamEvent: created progress message id={}", sentMessageId);
            }
        } else {
            messageSender.editHtml(chatId, progressMessageId, progressHtml, true);
            log.info("FSM agentStreamEvent: updated progress message id={}", progressMessageId);
        }
        ctx.setAlreadySentInStream(true);
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
