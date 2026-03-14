package io.github.ngirchev.opendaimon.telegram.command.handler;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import io.github.ngirchev.opendaimon.bulkhead.exception.AccessDeniedException;
import io.github.ngirchev.opendaimon.common.command.ICommandHandler;
import io.github.ngirchev.opendaimon.common.service.AIUtils;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractTelegramCommandHandlerWithResponseSend implements
        ICommandHandler<TelegramCommandType, TelegramCommand, Void>,
        TelegramSupportedCommandProvider {

    protected final ObjectProvider<TelegramBot> telegramBotProvider;
    protected final TypingIndicatorService typingIndicatorService;
    protected final MessageLocalizationService messageLocalizationService;

    @PostConstruct
    public void init() {
        log.info("Handler initialized: {} (priority: {})",
            this.getClass().getSimpleName(),
            this.priority());
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public final Void handle(TelegramCommand command) {
        try {
            typingIndicatorService.startTyping(command.telegramId());
            try {
                String message = handleInner(command);
                if (StringUtils.isNoneBlank(message)) {
                    sendMessage(command.telegramId(), message);
                }
            } catch (AccessDeniedException e) {
                log.error("Access denied for user {}: {}", command.telegramId(), e.getMessage());
                sendErrorMessage(command.telegramId(), messageLocalizationService.getMessage("common.error.access.denied", command.languageCode()));
            } catch (TelegramCommandHandlerException e) {
                log.error(AbstractTelegramCommandHandler.LOG_ERROR_PROCESSING_MESSAGE, e.getMessage(), e);
                sendErrorMessage(command.telegramId(), e.getMessage());
            } catch (Exception e) {
                if (AIUtils.shouldLogWithoutStacktrace(e)) {
                    log.error(AbstractTelegramCommandHandler.LOG_ERROR_PROCESSING_MESSAGE, AIUtils.getRootCauseMessage(e));
                } else {
                    log.error(AbstractTelegramCommandHandler.LOG_ERROR_PROCESSING_MESSAGE, e.getMessage(), e);
                }
                sendErrorMessage(command.telegramId(), messageLocalizationService.getMessage("common.error.processing", command.languageCode()));
            }
        } finally {
            typingIndicatorService.stopTyping(command.telegramId());
        }
        return null;
    }

    protected abstract String handleInner(TelegramCommand command) throws TelegramCommandHandlerException, TelegramApiException;

    public void sendMessage(Long chatId, String text) {
        sendMessage(chatId, text, null);
    }

    public void sendMessage(Long chatId, String text, Integer replyToMessageId) {
        sendMessage(chatId, text, replyToMessageId, null);
    }

    public void sendMessage(Long chatId, String text, Integer replyToMessageId, String languageCode) {
        try {
            telegramBotProvider.getObject().sendMessage(chatId, text, replyToMessageId);
        } catch (TelegramApiException e) {
            String msg = messageLocalizationService.getMessage("common.error.send.failed", languageCode);
            throw new TelegramCommandHandlerException(msg, e);
        }
    }

    public Integer sendMessageAndGetId(Long chatId, String text, Integer replyToMessageId) {
        return sendMessageAndGetId(chatId, text, replyToMessageId, null);
    }

    public Integer sendMessageAndGetId(Long chatId, String text, Integer replyToMessageId, String languageCode) {
        try {
            return telegramBotProvider.getObject().sendMessageAndGetId(chatId, text, replyToMessageId);
        } catch (TelegramApiException e) {
            String msg = messageLocalizationService.getMessage("common.error.send.failed", languageCode);
            throw new TelegramCommandHandlerException(msg, e);
        }
    }

    public void sendErrorMessage(Long chatId, String errorMessage) {
        sendErrorMessage(chatId, errorMessage, null);
    }

    public void sendErrorMessage(Long chatId, String errorMessage, Integer replyToMessageId) {
        sendErrorMessage(chatId, errorMessage, replyToMessageId, null);
    }

    public void sendErrorMessage(Long chatId, String errorMessage, Integer replyToMessageId, String languageCode) {
        try {
            telegramBotProvider.getObject().sendErrorMessage(chatId, errorMessage, replyToMessageId);
        } catch (TelegramApiException e) {
            String msg = messageLocalizationService.getMessage("common.error.send.error.failed", languageCode);
            throw new TelegramCommandHandlerException(msg, e);
        }
    }

    @Override
    public String getSupportedCommandText(String languageCode) {
        return null;
    }
}
