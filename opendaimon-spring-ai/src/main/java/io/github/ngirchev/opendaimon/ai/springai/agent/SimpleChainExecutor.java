package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentResult;
import io.github.ngirchev.opendaimon.common.agent.AgentState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Simple chain executor — single LLM call without tools.
 *
 * <p>Fast path for simple questions where the ReAct loop is unnecessary.
 * No tool calling, no iterations — just one prompt and one response.
 */
@Slf4j
public class SimpleChainExecutor implements AgentExecutor {

    private final ChatModel chatModel;

    public SimpleChainExecutor(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public AgentResult execute(AgentRequest request) {
        Instant start = Instant.now();
        log.info("SimpleChain execution: task='{}'", request.task());

        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage("You are a helpful AI assistant. Answer the user's question directly and concisely."),
                    new UserMessage(request.task())
            ));

            ChatResponse response = chatModel.call(prompt);
            String answer = response != null && response.getResult() != null
                    ? response.getResult().getOutput().getText()
                    : null;

            Duration duration = Duration.between(start, Instant.now());
            log.info("SimpleChain completed: duration={}ms", duration.toMillis());

            return new AgentResult(answer, List.of(), AgentState.COMPLETED, 0, duration);
        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            log.error("SimpleChain failed: {}", e.getMessage(), e);
            return new AgentResult(null, List.of(), AgentState.FAILED, 0, duration);
        }
    }
}
