package ru.girchev.aibot.telegram.command.handler.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import ru.girchev.aibot.common.command.ICommand;
import ru.girchev.aibot.common.model.ConversationThread;
import ru.girchev.aibot.common.model.AIBotMessage;
import ru.girchev.aibot.common.model.MessageRole;
import ru.girchev.aibot.common.repository.ConversationThreadRepository;
import ru.girchev.aibot.common.repository.AIBotMessageRepository;
import ru.girchev.aibot.common.service.MessageLocalizationService;
import ru.girchev.aibot.telegram.TelegramBot;
import ru.girchev.aibot.telegram.command.TelegramCommand;
import ru.girchev.aibot.telegram.command.TelegramCommandType;
import ru.girchev.aibot.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import ru.girchev.aibot.telegram.command.handler.TelegramCommandHandlerException;
import ru.girchev.aibot.telegram.model.TelegramUser;
import ru.girchev.aibot.telegram.service.TelegramUserService;
import ru.girchev.aibot.telegram.service.TypingIndicatorService;

import java.util.List;
import java.util.Optional;

/**
 * Handler for /history command to view conversation history.
 */
@Slf4j
public class HistoryTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {
    
    private final ConversationThreadRepository threadRepository;
    private final AIBotMessageRepository messageRepository;
    private final TelegramUserService userService;
    
    public HistoryTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            ConversationThreadRepository threadRepository,
            AIBotMessageRepository messageRepository,
            TelegramUserService userService) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.threadRepository = threadRepository;
        this.messageRepository = messageRepository;
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
                && commandType.command().equals(TelegramCommand.HISTORY)
                && !telegramCommand.update().hasCallbackQuery();
    }
    
    @Override
    public String handleInner(TelegramCommand command) throws TelegramCommandHandlerException {
        org.telegram.telegrambots.meta.api.objects.Message message = command.update().getMessage();
        if (message == null) {
            throw new TelegramCommandHandlerException(command.telegramId(), "Message is required for history command");
        }
        
        TelegramUser user = userService.getOrCreateUser(message.getFrom());
        
        // Get active thread
        Optional<ConversationThread> threadOpt = threadRepository.findMostRecentActiveThread(user);
        if (threadOpt.isEmpty()) {
            return "❌ You have no active conversation. Start one by sending a message.";
        }
        
        ConversationThread thread = threadOpt.get();
        
        // Load message history
        List<AIBotMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread);
        
        if (messages.isEmpty()) {
            return "📝 Conversation history is empty.\n\nThread ID: `" + thread.getThreadKey().substring(0, 8) + "...`";
        }
        
        // Build history message
        StringBuilder history = new StringBuilder();
        history.append("📜 Conversation history\n\n");
        history.append("Thread ID: `").append(thread.getThreadKey().substring(0, 8)).append("...`\n");
        history.append("Total messages: ").append(messages.size()).append("\n\n");
        
        int messageCount = 0;
        AIBotMessage lastUserMessage = null;
        for (AIBotMessage msg : messages) {
            if (msg.getRole() == MessageRole.USER) {
                messageCount++;
                lastUserMessage = msg;
                history.append("💬 ").append(messageCount).append(". ").append(msg.getContent()).append("\n");
            } else if (msg.getRole() == MessageRole.ASSISTANT && lastUserMessage != null) {
                String responseText = msg.getContent();
                // Limit response length for readability
                if (responseText != null && responseText.length() > 200) {
                    responseText = responseText.substring(0, 197) + "...";
                }
                history.append("🤖 ").append(responseText != null ? responseText : "⏳ Waiting for response...").append("\n\n");
                lastUserMessage = null;
            }
            
            // Limit messages for readability (last 10 turns = 20 messages)
            if (messageCount >= 10 && messages.size() > 20) {
                int remaining = (messages.size() - messageCount * 2) / 2;
                history.append("... and ").append(remaining).append(" more messages.\n");
                history.append("Use /newthread to start a new conversation.");
                break;
            }
        }
        
        // If there is an unprocessed USER message
        if (lastUserMessage != null) {
            history.append("⏳ Waiting for response...\n\n");
        }
        
        return history.toString();
    }
    
    @Override
    public String getSupportedCommandText(String languageCode) {
        return messageLocalizationService.getMessage("telegram.command.history.desc", languageCode);
    }
}
