package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import io.github.ngirchev.opendaimon.common.agent.AgentChatCommand;
import io.github.ngirchev.opendaimon.common.agent.AgentCommandHandler;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.AbstractTelegramCommandHandler;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;

import java.util.Map;

/**
 * Telegram command handler for agent mode.
 *
 * <p>Intercepts messages with {@code /agent} command, delegates to {@link AgentCommandHandler}
 * (through the standard command bus), and sends the agent's final answer back to the Telegram chat.
 *
 * <p>Usage: {@code /agent Search for Java 21 features and summarize them}
 */
@Slf4j
public class AgentTelegramCommandHandler extends AbstractTelegramCommandHandler {

    public static final String AGENT_COMMAND = "/agent";
    private static final int AGENT_HANDLER_PRIORITY = 5;

    private final AgentCommandHandler agentCommandHandler;

    public AgentTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            AgentCommandHandler agentCommandHandler) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.agentCommandHandler = agentCommandHandler;
    }

    @Override
    public boolean canHandle(ICommand<TelegramCommandType> command) {
        if (!(command instanceof TelegramCommand)) return false;
        var commandType = command.commandType();
        if (commandType == null || commandType.command() == null) return false;
        return AGENT_COMMAND.equals(commandType.command());
    }

    @Override
    public int priority() {
        return AGENT_HANDLER_PRIORITY;
    }

    @Override
    protected void handleInner(TelegramCommand command) throws TelegramApiException {
        String userText = command.userText();
        if (userText == null || userText.isBlank()) {
            sendMessage(command.telegramId(),
                    "Usage: /agent <task description>\n\nExample: /agent Search for Java 21 features and summarize them");
            return;
        }

        Message message = command.update().getMessage();
        Integer replyToMessageId = message != null ? message.getMessageId() : null;

        log.info("Agent command received: userId={}, task='{}'", command.userId(), userText);

        String conversationId = "telegram:" + command.userId();

        AgentChatCommand agentCommand = new AgentChatCommand(
                command.userId(), userText, conversationId,
                Map.of("channel", "telegram", "userId", String.valueOf(command.userId())));

        AIResponse aiResponse = agentCommandHandler.handle(agentCommand);
        String responseText = extractTextFromResponse(aiResponse);

        log.info("Agent completed: responseLength={}", responseText != null ? responseText.length() : 0);

        sendMessage(command.telegramId(), responseText, replyToMessageId);
    }

    @SuppressWarnings("unchecked")
    private String extractTextFromResponse(AIResponse aiResponse) {
        Map<String, Object> data = aiResponse.toMap();
        if (data.containsKey("choices")) {
            var choices = (java.util.List<Map<String, Object>>) data.get("choices");
            if (!choices.isEmpty()) {
                var message = (Map<String, Object>) choices.getFirst().get("message");
                if (message != null) {
                    return (String) message.get("content");
                }
            }
        }
        return "Agent completed (no response text)";
    }

    @Override
    public String getSupportedCommandText(String languageCode) {
        return "/agent - AI agent mode (autonomous task execution with tools)";
    }
}
