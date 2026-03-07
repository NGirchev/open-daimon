package ru.girchev.aibot.telegram.command.handler;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.girchev.aibot.bulkhead.exception.AccessDeniedException;
import ru.girchev.aibot.common.command.ICommandHandler;
import ru.girchev.aibot.common.service.AIUtils;
import ru.girchev.aibot.telegram.TelegramBot;
import ru.girchev.aibot.telegram.command.TelegramCommand;
import ru.girchev.aibot.telegram.command.TelegramCommandType;
import ru.girchev.aibot.telegram.service.TypingIndicatorService;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractTelegramCommandHandler implements
        ICommandHandler<TelegramCommandType, TelegramCommand, Void>,
        TelegramSupportedCommandProvider {

    protected final ObjectProvider<TelegramBot> telegramBotProvider;
    protected final TypingIndicatorService typingIndicatorService;

    @PostConstruct
    public void init() {
        log.info("Инициализирован обработчик: {} (приоритет: {})", 
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
                sendErrorMessage(command.telegramId(), "Доступ ограничен. Пожалуйста, попробуйте позже.");
            } catch (TelegramCommandHandlerException e) {
                log.error("Error processing message: {}", e.getMessage(), e);
                sendErrorMessage(command.telegramId(), e.getMessage());
            } catch (Exception e) {
                if (AIUtils.shouldLogWithoutStacktrace(e)) {
                    log.error("Error processing message: {}", AIUtils.getRootCauseMessage(e));
                } else {
                    log.error("Error processing message: {}", e.getMessage(), e);
                }
                sendErrorMessage(command.telegramId(), "Произошла ошибка при обработке сообщения");
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
        try {
            telegramBotProvider.getObject().sendMessage(chatId, text, replyToMessageId);
        } catch (TelegramApiException e) {
            throw new TelegramCommandHandlerException("Ошибка отправки сообщения в Telegram", e);
        }
    }

    public void sendErrorMessage(Long chatId, String errorMessage) {
        sendErrorMessage(chatId, errorMessage, null);
    }

    public void sendErrorMessage(Long chatId, String errorMessage, Integer replyToMessageId) {
        try {
            telegramBotProvider.getObject().sendErrorMessage(chatId, errorMessage, replyToMessageId);
        } catch (TelegramApiException e) {
            throw new TelegramCommandHandlerException("Ошибка отправки сообщения об ошибке в Telegram", e);
        }
    }
}
