package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.exception.DocumentContentNotExtractableException;
import io.github.ngirchev.opendaimon.common.exception.UnsupportedModelCapabilityException;
import io.github.ngirchev.opendaimon.common.exception.UserMessageTooLongException;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.service.AIUtils;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.AbstractTelegramCommandHandler;
import io.github.ngirchev.opendaimon.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerContext;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerErrorType;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerEvent;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerState;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Telegram message handler that delegates processing to an FSM pipeline.
 *
 * <p>The FSM models the full lifecycle: user resolution → input validation →
 * message save → metadata preparation → AI command creation → response generation →
 * response save → response send.
 *
 * <p>Error handling is done after the FSM completes — the handler checks the terminal
 * state and dispatches to the appropriate error handling method based on the error type
 * stored in the context.
 */
@Slf4j
public class MessageTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {

    private final ExDomainFsm<MessageHandlerContext, MessageHandlerState, MessageHandlerEvent> handlerFsm;
    private final TelegramMessageService telegramMessageService;
    private final TelegramProperties telegramProperties;
    private final PersistentKeyboardService persistentKeyboardService;

    @SuppressWarnings("java:S107")
    public MessageTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            ExDomainFsm<MessageHandlerContext, MessageHandlerState, MessageHandlerEvent> handlerFsm,
            TelegramMessageService telegramMessageService,
            TelegramProperties telegramProperties,
            PersistentKeyboardService persistentKeyboardService) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.handlerFsm = handlerFsm;
        this.telegramMessageService = telegramMessageService;
        this.telegramProperties = telegramProperties;
        this.persistentKeyboardService = persistentKeyboardService;
    }

    @Override
    public boolean canHandle(ICommand<TelegramCommandType> command) {
        if (!(command instanceof TelegramCommand)) return false;
        var commandType = command.commandType();
        if (commandType == null || commandType.command() == null) return false;
        return commandType.command().equals(TelegramCommand.MESSAGE);
    }

    @Override
    public String handleInner(TelegramCommand command) {
        Message message = command.update().getMessage();

        // Create streaming callback that sends paragraphs to Telegram
        AtomicReference<Integer> replyToMessageId = new AtomicReference<>(message != null ? message.getMessageId() : null);
        MessageHandlerContext ctx = new MessageHandlerContext(command, message,
                htmlText -> {
                    sendMessage(command.telegramId(), htmlText, replyToMessageId.get());
                    replyToMessageId.set(null);
                });

        try {
            handlerFsm.handle(ctx, MessageHandlerEvent.HANDLE);
        } catch (Exception e) {
            // Action threw an exception that FSM didn't catch — classify and set on context
            if (ctx.getErrorType() == null) {
                ctx.classifyAndSetError(e);
            }
        }

        // Dispatch based on terminal state or error type
        if (ctx.isCompleted()) {
            sendSuccessResponse(ctx, command, message);
        } else if (ctx.isError() || ctx.hasError()) {
            dispatchError(ctx, command, message);
        }

        return null;
    }

    // --- Success response sending ---

    private void sendSuccessResponse(MessageHandlerContext ctx, TelegramCommand command, Message message) {
        if (ctx.isAlreadySentInStream()) {
            // Streaming: text already sent paragraph-by-paragraph, now send keyboard
            persistentKeyboardService.sendKeyboard(
                    command.telegramId(), ctx.getTelegramUser().getId(),
                    ctx.getThread(), ctx.getResponseModel());
        } else {
            // Non-streaming: send text + keyboard (uses parent's sendMessage for proper exception wrapping)
            String htmlText = AIUtils.convertMarkdownToHtml(ctx.getResponseText().orElseThrow());
            ReplyKeyboardMarkup keyboard = persistentKeyboardService.buildKeyboardMarkup(
                    ctx.getTelegramUser().getId(), ctx.getThread());
            sendMessage(command.telegramId(), htmlText, message.getMessageId(), keyboard);
        }
    }

    // --- Error dispatching ---

    private void dispatchError(MessageHandlerContext ctx, TelegramCommand command, Message message) {
        MessageHandlerErrorType errorType = ctx.getErrorType();
        if (errorType == null) {
            errorType = MessageHandlerErrorType.GENERAL;
        }

        switch (errorType) {
            case INPUT_EMPTY -> handleEmptyInput(ctx, command, message);
            case MESSAGE_TOO_LONG -> handleMessageTooLong(ctx, command, message);
            case DOCUMENT_NOT_EXTRACTABLE -> handleDocumentError(ctx, command, message);
            case UNSUPPORTED_CAPABILITY -> handleCapabilityError(ctx, command, message);
            case SUMMARIZATION_FAILED -> handleSummarizationFailed(command, message);
            case EMPTY_RESPONSE -> handleEmptyResponse(ctx, command, message);
            case GENERAL -> handleGeneralError(ctx, command, message);
        }
    }

    private void handleEmptyInput(MessageHandlerContext ctx, TelegramCommand command, Message message) {
        String emptyRequestText = messageLocalizationService.getMessage(
                "telegram.message.empty.after.mention",
                command.languageCode(),
                formatBotMention());
        Integer replyToMessageId = message != null ? message.getMessageId() : null;
        sendErrorMessage(command.telegramId(), emptyRequestText, replyToMessageId);
    }

    private void handleMessageTooLong(MessageHandlerContext ctx, TelegramCommand command, Message message) {
        UserMessageTooLongException e = (UserMessageTooLongException) ctx.getException();
        log.warn("Message exceeds token limit: {}", e.getMessage());
        Integer replyToMessageId = message != null ? message.getMessageId() : null;
        String errorText = e.getEstimatedTokens() > 0 && e.getMaxAllowed() > 0
                ? messageLocalizationService.getMessage("common.error.message.too.long",
                        command.languageCode(), e.getEstimatedTokens(), e.getMaxAllowed())
                : e.getMessage();
        sendErrorMessage(command.telegramId(), errorText, replyToMessageId);
    }

    private void handleDocumentError(MessageHandlerContext ctx, TelegramCommand command, Message message) {
        DocumentContentNotExtractableException e = (DocumentContentNotExtractableException) ctx.getException();
        log.warn("Could not extract text from document: {}", e.getMessage());
        Integer replyToMessageId = message != null ? message.getMessageId() : null;
        saveErrorResponse(ctx, e.getMessage());
        sendErrorMessage(command.telegramId(), e.getMessage(), replyToMessageId);
    }

    private void handleCapabilityError(MessageHandlerContext ctx, TelegramCommand command, Message message) {
        UnsupportedModelCapabilityException e = (UnsupportedModelCapabilityException) ctx.getException();
        log.warn("Model capability mismatch: {}", e.getMessage());
        Integer replyToMessageId = message != null ? message.getMessageId() : null;
        String errorText = e.getModelId() != null
                ? messageLocalizationService.getMessage(
                        "common.error.model.unsupported.capability",
                        command.languageCode(), e.getModelId(), e.getMissingCapabilities())
                : e.getMessage();
        saveErrorResponse(ctx, errorText);
        sendErrorMessage(command.telegramId(), errorText, replyToMessageId);
    }

    private void handleSummarizationFailed(TelegramCommand command, Message message) {
        log.warn("Summarization failed, notifying user to start new thread");
        Integer replyToMessageId = message != null ? message.getMessageId() : null;
        String errorText = messageLocalizationService.getMessage(
                "telegram.summarization.failed", command.languageCode());
        sendErrorMessage(command.telegramId(), errorText, replyToMessageId);
    }

    private void handleEmptyResponse(MessageHandlerContext ctx, TelegramCommand command, Message message) {
        String detailedError = ctx.getResponseError().orElse(AIUtils.CONTENT_IS_EMPTY);
        log.warn("Empty content from model: {}. usefulResponseData={}",
                detailedError, ctx.getUsefulResponseData());
        telegramMessageService.saveAssistantErrorMessage(
                ctx.getTelegramUser(), detailedError,
                ctx.getModelCapabilities().toString(),
                ctx.getAssistantRole() != null ? ctx.getAssistantRole().getContent() : null,
                ctx.getUsefulResponseData() != null && !ctx.getUsefulResponseData().isEmpty()
                        ? ctx.getUsefulResponseData().toString() : null,
                ctx.getThread());
        String userMessage = messageLocalizationService.getMessage(
                "common.error.processing", command.languageCode());
        sendErrorMessage(command.telegramId(), userMessage, message.getMessageId());
    }

    private void handleGeneralError(MessageHandlerContext ctx, TelegramCommand command, Message message) {
        Exception e = ctx.getException();
        if (AIUtils.shouldLogWithoutStacktrace(e)) {
            log.error(AbstractTelegramCommandHandler.LOG_ERROR_PROCESSING_MESSAGE,
                    AIUtils.getRootCauseMessage(e));
        } else {
            log.error(AbstractTelegramCommandHandler.LOG_ERROR_PROCESSING_MESSAGE, e);
        }
        String userFacingMessage = messageLocalizationService.getMessage(
                "common.error.processing", command.languageCode());
        saveErrorResponse(ctx, userFacingMessage);
        Integer replyToMessageId = message != null ? message.getMessageId() : null;
        sendErrorMessage(command.telegramId(), userFacingMessage, replyToMessageId);
    }

    private void saveErrorResponse(MessageHandlerContext ctx, String errorText) {
        OpenDaimonMessage userMessage = ctx.getUserMessage();
        if (userMessage != null && userMessage.getUser() instanceof TelegramUser telegramUser) {
            String errorRoleContent = ctx.getAssistantRole() != null
                    ? ctx.getAssistantRole().getContent() : null;
            telegramMessageService.saveAssistantErrorMessage(
                    telegramUser, errorText,
                    ctx.getModelCapabilities().toString(),
                    errorRoleContent, null,
                    ctx.getThread());
        }
    }

    // --- Utility ---

    @Override
    public String getSupportedCommandText(String languageCode) {
        return null;
    }

    private String formatBotMention() {
        String normalized = telegramProperties.getNormalizedBotUsername();
        return normalized != null ? normalized : "@bot";
    }
}
