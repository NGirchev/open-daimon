package io.github.ngirchev.opendaimon.common.agent;

import io.github.ngirchev.opendaimon.common.ai.AIGateways;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;
import io.github.ngirchev.opendaimon.common.ai.response.MapResponse;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.command.ICommandHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command handler that delegates agent-mode requests to {@link AgentExecutor}.
 *
 * <p>This handler is registered in the {@code CommandHandlerRegistry} and
 * intercepts commands of type {@link AgentChatCommand}. It has higher priority
 * than regular message handlers so it processes agent requests first.
 *
 * <p>The handler is channel-agnostic — it works for Telegram, REST, and UI.
 * Each channel creates an {@link AgentChatCommand} when the user invokes
 * agent mode (e.g., via /agent command).
 */
@Slf4j
public class AgentCommandHandler implements
        ICommandHandler<AgentCommandType, AgentChatCommand, AIResponse> {

    private static final int AGENT_HANDLER_PRIORITY = 10;

    private final AgentExecutor agentExecutor;
    private final int defaultMaxIterations;

    public AgentCommandHandler(AgentExecutor agentExecutor, int defaultMaxIterations) {
        this.agentExecutor = agentExecutor;
        this.defaultMaxIterations = defaultMaxIterations;
    }

    @Override
    public int priority() {
        return AGENT_HANDLER_PRIORITY;
    }

    @Override
    public boolean canHandle(ICommand<AgentCommandType> command) {
        return command instanceof AgentChatCommand;
    }

    @Override
    public AIResponse handle(AgentChatCommand command) {
        log.info("Agent handler received command: userId={}, taskLength={}",
                command.userId(), command.userText() != null ? command.userText().length() : 0);

        int maxIterations = command.maxIterations() != null
                ? command.maxIterations()
                : defaultMaxIterations;

        AgentRequest request = new AgentRequest(
                command.userText(),
                command.conversationId(),
                command.metadata(),
                maxIterations,
                command.enabledTools() != null ? command.enabledTools() : Set.of()
        );

        AgentResult result = agentExecutor.execute(request);

        log.info("Agent handler completed: state={}, iterations={}, duration={}ms",
                result.terminalState(), result.iterationsUsed(), result.totalDuration().toMillis());

        return toAIResponse(result);
    }

    private AIResponse toAIResponse(AgentResult result) {
        String text = result.finalAnswer() != null
                ? result.finalAnswer()
                : "Agent finished in state: " + result.terminalState();

        Map<String, Object> rawData = Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of("content", text),
                        "finish_reason", result.isSuccess() ? "stop" : "error"
                ))
        );
        return new MapResponse(AIGateways.SPRINGAI, rawData);
    }
}
