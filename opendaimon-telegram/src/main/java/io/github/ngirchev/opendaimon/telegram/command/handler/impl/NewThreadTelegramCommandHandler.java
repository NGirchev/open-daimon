package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.Message;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.service.ConversationThreadService;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import io.github.ngirchev.opendaimon.telegram.command.handler.TelegramCommandHandlerException;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;

import java.util.Optional;

/**
 * Handler for /newthread command to start a new conversation.
 */
@Slf4j
public class NewThreadTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {
    
    private final ConversationThreadService threadService;
    private final ConversationThreadRepository threadRepository;
    private final TelegramUserService userService;
    
    public NewThreadTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            ConversationThreadService threadService,
            ConversationThreadRepository threadRepository,
            TelegramUserService userService) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.threadService = threadService;
        this.threadRepository = threadRepository;
        this.userService = userService;
    }
    
    @Override
    public boolean canHandle(ICommand<TelegramCommandType> command) {
        if (!(command instanceof TelegramCommand telegramCommand)) {
            return false;
        }
        var commandType = command.commandType();
        return commandType != null
                && commandType.command() != null
                && commandType.command().equals(TelegramCommand.NEWTHREAD)
                && !telegramCommand.update().hasCallbackQuery();
    }
    
    @Override
    public String handleInner(TelegramCommand command) throws TelegramCommandHandlerException {
        Message message = command.update().getMessage();
        if (message == null) {
            throw new TelegramCommandHandlerException(command.telegramId(), "Message is required for newthread command");
        }
        
        TelegramUser user = userService.getOrCreateUser(message.getFrom());
        
        // Close current thread (if any active)
        Optional<ConversationThread> currentThread = threadRepository.findMostRecentActiveThread(user);
        boolean hadPreviousThread = currentThread.isPresent();
        currentThread.ifPresent(threadService::closeThread);
        
        // Create new thread
        ConversationThread newThread = threadService.createNewThread(user);
        
        // Build message depending on whether there was a previous conversation
        String responseMessage = "✅ New conversation started!\n\n" +
            "Thread ID: `" + newThread.getThreadKey().substring(0, 8) + "...`";
        if (hadPreviousThread) {
            responseMessage += "\n\nPrevious conversation history was saved.";
        }
        
        return responseMessage;
    }
    
    @Override
    public String getSupportedCommandText(String languageCode) {
        return messageLocalizationService.getMessage("telegram.command.newthread.desc", languageCode);
    }
}
