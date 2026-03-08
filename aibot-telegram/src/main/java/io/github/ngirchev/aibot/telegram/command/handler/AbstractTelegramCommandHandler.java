package io.github.ngirchev.aibot.telegram.command.handler;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import io.github.ngirchev.aibot.bulkhead.exception.AccessDeniedException;
import io.github.ngirchev.aibot.common.command.ICommandHandler;
import io.github.ngirchev.aibot.common.service.AIUtils;
import io.github.ngirchev.aibot.common.service.MessageLocalizationService;
import io.github.ngirchev.aibot.telegram.TelegramBot;
import io.github.ngirchev.aibot.telegram.command.TelegramCommand;
import io.github.ngirchev.aibot.telegram.command.TelegramCommandType;
import io.github.ngirchev.aibot.telegram.service.TypingIndicatorService;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractTelegramCommandHandler implements
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
                handleInner(command);
            } catch (AccessDeniedException e) {
                log.error("Access denied for user {}: {}", command.telegramId(), e.getMessage());
                sendErrorMessage(command.telegramId(), messageLocalizationService.getMessage("common.error.access.denied", command.languageCode()));
            } catch (TelegramCommandHandlerException e) {
                log.error("Error processing message: {}", e.getMessage(), e);
                sendErrorMessage(command.telegramId(), e.getMessage());
            } catch (Exception e) {
                if (AIUtils.shouldLogWithoutStacktrace(e)) {
                    log.error("Error processing message: {}", AIUtils.getRootCauseMessage(e));
                } else {
                    log.error("Error processing message: {}", e.getMessage(), e);
                }
                sendErrorMessage(command.telegramId(), messageLocalizationService.getMessage("common.error.processing", command.languageCode()));
            }
        } finally {
            typingIndicatorService.stopTyping(command.telegramId());
        }
        return null;
    }

    protected abstract void handleInner(TelegramCommand command) throws TelegramCommandHandlerException, TelegramApiException;

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
