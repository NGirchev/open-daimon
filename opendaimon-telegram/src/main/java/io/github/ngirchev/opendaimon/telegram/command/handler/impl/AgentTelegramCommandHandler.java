package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentResult;
import io.github.ngirchev.opendaimon.common.agent.AgentStepResult;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.AbstractTelegramCommandHandler;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;

import java.util.Map;
import java.util.Set;

/**
 * Telegram command handler for agent mode.
 *
 * <p>Intercepts messages with {@code /agent} command, delegates to {@link AgentExecutor},
 * and sends the agent's final answer back to the Telegram chat.
 *
 * <p>Usage: {@code /agent Search for Java 21 features and summarize them}
 */
@Slf4j
public class AgentTelegramCommandHandler extends AbstractTelegramCommandHandler {

    public static final String AGENT_COMMAND = "/agent";
    private static final int AGENT_HANDLER_PRIORITY = 5;

    private final AgentExecutor agentExecutor;
    private final int maxIterations;

    public AgentTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            AgentExecutor agentExecutor,
            int maxIterations) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.agentExecutor = agentExecutor;
        this.maxIterations = maxIterations;
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

        String conversationId = resolveConversationId(command);

        AgentRequest request = new AgentRequest(
                userText,
                conversationId,
                Map.of("channel", "telegram", "userId", String.valueOf(command.userId())),
                maxIterations,
                Set.of()
        );

        AgentResult result = agentExecutor.execute(request);

        String responseText = formatAgentResponse(result);

        log.info("Agent completed: state={}, iterations={}, duration={}ms, responseLength={}",
                result.terminalState(), result.iterationsUsed(),
                result.totalDuration().toMillis(), responseText.length());

        sendMessage(command.telegramId(), responseText, replyToMessageId);
    }

    private String resolveConversationId(TelegramCommand command) {
        return "telegram:" + command.userId();
    }

    private String formatAgentResponse(AgentResult result) {
        var sb = new StringBuilder();

        if (!result.steps().isEmpty()) {
            sb.append("🔄 Agent completed in ").append(result.iterationsUsed())
                    .append(" step(s) (").append(result.totalDuration().toSeconds()).append("s)\n\n");

            for (AgentStepResult step : result.steps()) {
                if (step.action() != null) {
                    sb.append("🔧 ").append(step.action()).append('\n');
                }
            }
            sb.append('\n');
        }

        if (result.isSuccess() && result.finalAnswer() != null) {
            sb.append(result.finalAnswer());
        } else {
            sb.append("⚠️ Agent finished with status: ").append(result.terminalState());
            if (result.finalAnswer() != null) {
                sb.append("\n\n").append(result.finalAnswer());
            }
        }

        return sb.toString();
    }

    @Override
    public String getSupportedCommandText(String languageCode) {
        return "agent - AI agent mode (autonomous task execution with tools)";
    }
}
