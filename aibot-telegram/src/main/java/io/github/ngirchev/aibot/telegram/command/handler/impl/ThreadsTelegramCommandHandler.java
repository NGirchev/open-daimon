package io.github.ngirchev.aibot.telegram.command.handler.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import io.github.ngirchev.aibot.common.command.ICommand;
import io.github.ngirchev.aibot.common.model.ConversationThread;
import io.github.ngirchev.aibot.common.repository.ConversationThreadRepository;
import io.github.ngirchev.aibot.common.service.ConversationThreadService;
import io.github.ngirchev.aibot.common.service.MessageLocalizationService;
import io.github.ngirchev.aibot.telegram.TelegramBot;
import io.github.ngirchev.aibot.telegram.command.TelegramCommand;
import io.github.ngirchev.aibot.telegram.command.TelegramCommandType;
import io.github.ngirchev.aibot.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import io.github.ngirchev.aibot.telegram.command.handler.TelegramCommandHandlerException;
import io.github.ngirchev.aibot.telegram.model.TelegramUser;
import io.github.ngirchev.aibot.telegram.service.TelegramUserService;
import io.github.ngirchev.aibot.telegram.service.TypingIndicatorService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Handler for /threads command to list all conversations.
 */
@Slf4j
public class ThreadsTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {
    
    private static final String CALLBACK_PREFIX = "THREADS_";
    private static final String THREAD_PREFIX = "Conversation ";
    
    private final ConversationThreadRepository threadRepository;
    private final ConversationThreadService threadService;
    private final TelegramUserService userService;
    
    public ThreadsTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            ConversationThreadRepository threadRepository,
            ConversationThreadService threadService,
            TelegramUserService userService) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.threadRepository = threadRepository;
        this.threadService = threadService;
        this.userService = userService;
    }
    
    @Override
    public boolean canHandle(ICommand<TelegramCommandType> command) {
        if (!(command instanceof TelegramCommand telegramCommand)) {
            return false;
        }
        var commandType = command.commandType();
        if (commandType == null || commandType.command() == null) {
            return false;
        }
        
        // Handle plain /threads command
        if (commandType.command().equals(TelegramCommand.THREADS) && !telegramCommand.update().hasCallbackQuery()) {
            return true;
        }
        
        // Handle callback query for conversation selection
        if (telegramCommand.update().hasCallbackQuery()) {
            CallbackQuery cq = telegramCommand.update().getCallbackQuery();
            return cq.getData() != null && cq.getData().startsWith(CALLBACK_PREFIX);
        }
        
        return false;
    }
    
    @Override
    public String handleInner(TelegramCommand command) throws TelegramCommandHandlerException {
        // Handle callback query for conversation selection
        if (command.update().hasCallbackQuery()) {
            handleCallbackQuery(command);
            return null; // Return null so base class does not send a message
        }
        
        // Handle plain /threads command
        Message message = command.update().getMessage();
        if (message == null) {
            throw new TelegramCommandHandlerException(command.telegramId(), "Message is required for threads command");
        }
        
        TelegramUser user = userService.getOrCreateUser(message.getFrom());
        
        // Get all threads (active and inactive)
        List<ConversationThread> allThreads = threadRepository.findByUserOrderByLastActivityAtDesc(user);
        
        if (allThreads.isEmpty()) {
            return "📝 You have no conversations. Start a new one by sending a message.";
        }

        // Build message with thread list
        StringBuilder threadsList = new StringBuilder();
        threadsList.append("📋 Select a conversation:\n\n");

        // Limit threads for menu (first 20)
        int threadsToShow = Math.min(allThreads.size(), 20);
        
        for (int i = 0; i < threadsToShow; i++) {
            ConversationThread thread = allThreads.get(i);
            threadsList.append((i + 1)).append(". ");
            
            // Show active status
            if (Boolean.TRUE.equals(thread.getIsActive())) {
                threadsList.append("✅ ");
            } else {
                threadsList.append("🔒 ");
            }
            
            if (thread.getTitle() != null && !thread.getTitle().isEmpty()) {
                threadsList.append(thread.getTitle());
            } else {
                threadsList.append(THREAD_PREFIX).append(thread.getThreadKey().substring(0, 8));
            }
            
            threadsList.append("\n");
        }
        
        if (allThreads.size() > 20) {
            threadsList.append("\n... and ").append(allThreads.size() - 20).append(" more.");
        }
        
        // Send message with menu
        sendMessageWithMenu(command.telegramId(), threadsList.toString(), command);
        return null; // Return null as message already sent
    }
    
    private void handleCallbackQuery(TelegramCommand command) throws TelegramCommandHandlerException {
        CallbackQuery cq = command.update().getCallbackQuery();
        String callbackData = cq.getData();
        
        if (callbackData == null || !callbackData.startsWith(CALLBACK_PREFIX)) {
            throw new TelegramCommandHandlerException(command.telegramId(), "Invalid callback data");
        }
        
        // Extract threadKey from callback data
        String threadKey = callbackData.substring(CALLBACK_PREFIX.length());
        
        TelegramUser user = userService.getOrCreateUser(cq.getFrom());
        
        // Find thread by key
        Optional<ConversationThread> threadOpt = threadService.findByThreadKey(threadKey);
        if (threadOpt.isEmpty()) {
            ackCallback(cq.getId(), "❌ Conversation not found");
            sendErrorMessage(command.telegramId(), "Conversation not found");
            return;
        }
        
        ConversationThread thread = threadOpt.get();
        
        // Verify thread belongs to user
        if (!thread.getUser().getId().equals(user.getId())) {
            ackCallback(cq.getId(), "❌ Access denied");
            sendErrorMessage(command.telegramId(), "This conversation is not yours");
            return;
        }
        
        // Activate thread
        threadService.activateThread(user, thread);
        
        // Build success message
        String threadTitle = thread.getTitle() != null && !thread.getTitle().isEmpty() 
            ? thread.getTitle() 
            : THREAD_PREFIX + thread.getThreadKey().substring(0, 8);
        
        String responseMessage = "✅ Active conversation changed:\n\n" +
            "📝 " + threadTitle + "\n" +
            "ID: `" + thread.getThreadKey().substring(0, 8) + "...`";
        
        if (thread.getTotalMessages() != null && thread.getTotalMessages() > 0) {
            responseMessage += "\nMessages: " + thread.getTotalMessages();
        }
        
        ackCallback(cq.getId(), "✅ Conversation activated");
        sendMessage(command.telegramId(), responseMessage);
    }
    
    private void sendMessageWithMenu(Long chatId, String text, TelegramCommand command) throws TelegramCommandHandlerException {
        try {
            TelegramUser user = userService.getOrCreateUser(command.update().getMessage().getFrom());
            List<ConversationThread> allThreads = threadRepository.findByUserOrderByLastActivityAtDesc(user);
            
            if (allThreads.isEmpty()) {
                sendMessage(chatId, text);
                return;
            }
            
            // Build menu with button per conversation
            List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
            
            // Limit buttons (first 20)
            int threadsToShow = Math.min(allThreads.size(), 20);
            
            for (int i = 0; i < threadsToShow; i++) {
                ConversationThread thread = allThreads.get(i);
                
                // Build button text
                String buttonText = (i + 1) + ". ";
                if (Boolean.TRUE.equals(thread.getIsActive())) {
                    buttonText += "✅ ";
                } else {
                    buttonText += "🔒 ";
                }
                
                String threadTitle = thread.getTitle() != null && !thread.getTitle().isEmpty()
                    ? thread.getTitle()
                    : THREAD_PREFIX + thread.getThreadKey().substring(0, 8);
                
                // Limit button text length (Telegram max 64 chars)
                if (buttonText.length() + threadTitle.length() > 60) {
                    threadTitle = threadTitle.substring(0, 60 - buttonText.length() - 3) + "...";
                }
                buttonText += threadTitle;
                
                InlineKeyboardButton button = new InlineKeyboardButton(buttonText);
                button.setCallbackData(CALLBACK_PREFIX + thread.getThreadKey());
                
                keyboard.add(List.of(button));
            }
            
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup(keyboard);
            
            SendMessage msg = new SendMessage(chatId.toString(), text);
            msg.setReplyMarkup(markup);
            msg.setParseMode("Markdown");
            
            telegramBotProvider.getObject().execute(msg);
        } catch (TelegramApiException e) {
            throw new TelegramCommandHandlerException("Failed to send message to Telegram", e);
        }
    }
    
    private void ackCallback(String callbackQueryId, String text) {
        try {
            AnswerCallbackQuery ack = new AnswerCallbackQuery();
            ack.setCallbackQueryId(callbackQueryId);
            ack.setText(text);
            ack.setShowAlert(false);
            telegramBotProvider.getObject().execute(ack);
        } catch (TelegramApiException e) {
            log.error("Error acknowledging callback query", e);
        }
    }
    
    @Override
    public String getSupportedCommandText(String languageCode) {
        return messageLocalizationService.getMessage("telegram.command.threads.desc", languageCode);
    }
}
