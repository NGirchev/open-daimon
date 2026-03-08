package io.github.ngirchev.aibot.telegram.command.handler.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import io.github.ngirchev.aibot.common.command.ICommand;
import io.github.ngirchev.aibot.common.model.ConversationThread;
import io.github.ngirchev.aibot.common.model.AIBotMessage;
import io.github.ngirchev.aibot.common.model.MessageRole;
import io.github.ngirchev.aibot.common.repository.ConversationThreadRepository;
import io.github.ngirchev.aibot.common.repository.AIBotMessageRepository;
import io.github.ngirchev.aibot.common.service.MessageLocalizationService;
import io.github.ngirchev.aibot.telegram.TelegramBot;
import io.github.ngirchev.aibot.telegram.command.TelegramCommand;
import io.github.ngirchev.aibot.telegram.command.TelegramCommandType;
import io.github.ngirchev.aibot.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import io.github.ngirchev.aibot.telegram.command.handler.TelegramCommandHandlerException;
import io.github.ngirchev.aibot.telegram.model.TelegramUser;
import io.github.ngirchev.aibot.telegram.service.TelegramUserService;
import io.github.ngirchev.aibot.telegram.service.TypingIndicatorService;

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
        if (!(command instanceof TelegramCommand telegramCommand)) return false;
        return isHistoryCommand(telegramCommand);
    }

    private static boolean isHistoryCommand(TelegramCommand telegramCommand) {
        var commandType = telegramCommand.commandType();
        if (commandType == null || commandType.command() == null) return false;
        if (telegramCommand.update().hasCallbackQuery()) return false;
        return commandType.command().equals(TelegramCommand.HISTORY);
    }
    
    @Override
    public String handleInner(TelegramCommand command) throws TelegramCommandHandlerException {
        org.telegram.telegrambots.meta.api.objects.Message message = command.update().getMessage();
        if (message == null) {
            throw new TelegramCommandHandlerException(command.telegramId(), "Message is required for history command");
        }
        TelegramUser user = userService.getOrCreateUser(message.getFrom());
        Optional<ConversationThread> threadOpt = threadRepository.findMostRecentActiveThread(user);
        if (threadOpt.isEmpty()) {
            return "❌ You have no active conversation. Start one by sending a message.";
        }
        ConversationThread thread = threadOpt.get();
        List<AIBotMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread);
        if (messages.isEmpty()) {
            return "📝 Conversation history is empty.\n\nThread ID: `" + thread.getThreadKey().substring(0, 8) + "...`";
        }
        return buildHistoryText(thread, messages);
    }

    private static String buildHistoryText(ConversationThread thread, List<AIBotMessage> messages) {
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
                appendAssistantLine(history, msg.getContent());
                lastUserMessage = null;
            }
            if (messageCount >= 10 && messages.size() > 20) {
                int remaining = (messages.size() - messageCount * 2) / 2;
                history.append("... and ").append(remaining).append(" more messages.\n");
                history.append("Use /newthread to start a new conversation.");
                break;
            }
        }
        if (lastUserMessage != null) {
            history.append("⏳ Waiting for response...\n\n");
        }
        return history.toString();
    }

    private static void appendAssistantLine(StringBuilder history, String content) {
        String responseText = content != null && content.length() > 200 ? content.substring(0, 197) + "..." : content;
        history.append("🤖 ").append(responseText != null ? responseText : "⏳ Waiting for response...").append("\n\n");
    }
    
    @Override
    public String getSupportedCommandText(String languageCode) {
        return messageLocalizationService.getMessage("telegram.command.history.desc", languageCode);
    }
}
