package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentResult;
import io.github.ngirchev.opendaimon.common.agent.AgentState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple chain executor — single LLM call without tools.
 *
 * <p>Fast path for simple questions where the ReAct loop is unnecessary.
 * No tool calling, no iterations — just one prompt and one response.
 * Loads conversation history from {@link ChatMemory} to maintain context
 * across turns.
 */
@Slf4j
public class SimpleChainExecutor implements AgentExecutor {

    private static final String SYSTEM_PROMPT =
            "You are a helpful AI assistant. Answer the user's question directly and concisely.";

    private final ChatModel chatModel;
    private final ChatMemory chatMemory;

    public SimpleChainExecutor(ChatModel chatModel, ChatMemory chatMemory) {
        this.chatModel = chatModel;
        this.chatMemory = chatMemory;
    }

    @Override
    public AgentResult execute(AgentRequest request) {
        Instant start = Instant.now();
        log.info("SimpleChain execution: task='{}'", request.task());

        try {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(SYSTEM_PROMPT));
            loadConversationHistory(request, messages);
            messages.add(new UserMessage(request.task()));

            Prompt prompt = new Prompt(messages);

            ChatResponse response = chatModel.call(prompt);
            response.getResult();
            String answer = response.getResult().getOutput().getText();
            String modelName = response.getMetadata() != null ? response.getMetadata().getModel() : null;

            saveConversationHistory(request, answer);

            Duration duration = Duration.between(start, Instant.now());
            log.info("SimpleChain completed: duration={}ms, model={}", duration.toMillis(), modelName);

            return new AgentResult(answer, List.of(), AgentState.COMPLETED, 0, duration, modelName);
        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            log.error("SimpleChain failed: {}", e.getMessage(), e);
            return new AgentResult(null, List.of(), AgentState.FAILED, 0, duration, null);
        }
    }

    private void loadConversationHistory(AgentRequest request, List<Message> messages) {
        if (chatMemory == null || request.conversationId() == null) {
            return;
        }
        try {
            List<Message> history = chatMemory.get(request.conversationId());
            if (history == null || history.isEmpty()) {
                return;
            }
            for (Message msg : history) {
                if (msg.getMessageType() == MessageType.SYSTEM) {
                    if (!messages.isEmpty() && messages.getFirst() instanceof SystemMessage existing) {
                        messages.set(0, new SystemMessage(existing.getText() + "\n\n" + msg.getText()));
                    }
                } else {
                    messages.add(msg);
                }
            }
            log.info("SimpleChain: loaded {} history messages from ChatMemory", history.size());
        } catch (Exception e) {
            log.warn("SimpleChain: failed to load conversation history: {}", e.getMessage());
        }
    }

    private void saveConversationHistory(AgentRequest request, String answer) {
        if (chatMemory == null || request.conversationId() == null || answer == null) {
            return;
        }
        try {
            chatMemory.add(request.conversationId(), List.of(
                    new UserMessage(request.task()),
                    new AssistantMessage(answer)
            ));
            log.info("SimpleChain: saved user+assistant messages to ChatMemory");
        } catch (Exception e) {
            log.warn("SimpleChain: failed to save conversation history: {}", e.getMessage());
        }
    }
}
