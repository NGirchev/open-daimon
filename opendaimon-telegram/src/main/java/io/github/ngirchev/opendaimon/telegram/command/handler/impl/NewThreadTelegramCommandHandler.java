package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.Message;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.ThreadScopeKind;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.service.ConversationThreadService;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import io.github.ngirchev.opendaimon.telegram.command.handler.TelegramCommandHandlerException;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
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
    private final ObjectProvider<PersistentKeyboardService> persistentKeyboardServiceProvider;

    public NewThreadTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            ConversationThreadService threadService,
            ConversationThreadRepository threadRepository,
            TelegramUserService userService,
            ObjectProvider<PersistentKeyboardService> persistentKeyboardServiceProvider) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.threadService = threadService;
        this.threadRepository = threadRepository;
        this.userService = userService;
        this.persistentKeyboardServiceProvider = persistentKeyboardServiceProvider;
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
        Long chatId = command.telegramId();
        
        // Close current thread (if any active)
        Optional<ConversationThread> currentThread = threadRepository.findMostRecentActiveThread(
                ThreadScopeKind.TELEGRAM_CHAT, chatId);
        boolean hadPreviousThread = currentThread.isPresent();
        currentThread.ifPresent(threadService::closeThread);
        
        // Create new thread
        ConversationThread newThread = threadService.createNewThread(user, ThreadScopeKind.TELEGRAM_CHAT, chatId);

        // Reset the context-usage button to 0% immediately
        PersistentKeyboardService keyboardService = persistentKeyboardServiceProvider.getIfAvailable();
        if (keyboardService != null) {
            keyboardService.sendKeyboard(command.telegramId(), user.getId(), newThread);
        }

        String lang = user.getLanguageCode();
        String threadPreview = newThread.getThreadKey().substring(0, 8) + "...";
        String responseMessage = messageLocalizationService.getMessage(
                "telegram.newthread.body", lang, threadPreview);
        if (hadPreviousThread) {
            responseMessage += messageLocalizationService.getMessage("telegram.newthread.previous.saved", lang);
        }

        return responseMessage;
    }
    
    @Override
    public String getSupportedCommandText(String languageCode) {
        return messageLocalizationService.getMessage("telegram.command.newthread.desc", languageCode);
    }
}
