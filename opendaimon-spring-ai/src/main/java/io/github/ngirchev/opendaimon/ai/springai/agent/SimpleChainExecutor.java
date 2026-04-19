package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentResult;
import io.github.ngirchev.opendaimon.common.agent.AgentState;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private final PriorityRequestExecutor priorityRequestExecutor;

    public SimpleChainExecutor(ChatModel chatModel, ChatMemory chatMemory) {
        this(chatModel, chatMemory, null);
    }

    public SimpleChainExecutor(ChatModel chatModel, ChatMemory chatMemory,
                               PriorityRequestExecutor priorityRequestExecutor) {
        this.chatModel = chatModel;
        this.chatMemory = chatMemory;
        this.priorityRequestExecutor = priorityRequestExecutor;
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

            ChatOptions options = buildOptions(request);
            Prompt prompt = new Prompt(messages, options);

            ChatResponse response = callWithPriority(request, prompt);
            response.getResult();
            String rawText = response.getResult().getOutput().getText();
            String answer = AgentTextSanitizer.stripToolCallTags(
                    AgentTextSanitizer.stripThinkTags(rawText));
            String modelName = response.getMetadata().getModel();

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

    @Override
    public Flux<AgentStreamEvent> executeStream(AgentRequest request) {
        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        Flux<AgentStreamEvent> eventFlux = sink.asFlux();

        Flux.defer(() -> {
            try {
                sink.tryEmitNext(AgentStreamEvent.thinking(0));

                List<Message> messages = new ArrayList<>();
                messages.add(new SystemMessage(SYSTEM_PROMPT));
                loadConversationHistory(request, messages);
                messages.add(new UserMessage(request.task()));

                ChatOptions options = buildOptions(request);
                ChatResponse response = callWithPriority(request, new Prompt(messages, options));
                response.getResult();
                String rawText = response.getResult().getOutput().getText();
                String modelName = response.getMetadata() != null
                        ? response.getMetadata().getModel() : null;

                // Extract thinking content from metadata (OpenRouter) or <think> tags (Ollama)
                String reasoning = AgentTextSanitizer.extractReasoning(response);
                log.info("SimpleChain stream: model={}, rawTextLength={}, reasoningLength={}, rawFirst100='{}'",
                        modelName,
                        rawText != null ? rawText.length() : 0,
                        reasoning != null ? reasoning.length() : 0,
                        rawText != null ? rawText.substring(0, Math.min(100, rawText.length())) : "null");
                if (reasoning != null && !reasoning.isBlank()) {
                    sink.tryEmitNext(AgentStreamEvent.thinking(reasoning, 0));
                }

                String answer = AgentTextSanitizer.stripToolCallTags(
                        AgentTextSanitizer.stripThinkTags(rawText));
                saveConversationHistory(request, answer);

                if (modelName != null) {
                    sink.tryEmitNext(AgentStreamEvent.metadata(modelName, 0));
                }
                if (answer != null && !answer.isBlank()) {
                    sink.tryEmitNext(AgentStreamEvent.finalAnswer(answer, 0));
                } else {
                    sink.tryEmitNext(AgentStreamEvent.error("SimpleChain: empty response", 0));
                }
                sink.tryEmitComplete();
            } catch (Exception e) {
                log.error("SimpleChain stream failed: {}", e.getMessage(), e);
                sink.tryEmitNext(AgentStreamEvent.error(e.getMessage(), 0));
                sink.tryEmitError(e);
            }
            return Flux.empty();
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();

        return eventFlux;
    }

    /**
     * Delegates {@code chatModel.call(prompt)} through {@link PriorityRequestExecutor} so that
     * all LLM calls respect the per-user concurrency limits. When no executor is configured
     * (e.g. in tests using the two-argument constructor), the call is made directly.
     */
    private ChatResponse callWithPriority(AgentRequest request, Prompt prompt) {
        if (priorityRequestExecutor == null) {
            return chatModel.call(prompt);
        }
        Long userId = SummaryModelInvoker.resolveUserId(
                request.metadata() != null ? request.metadata() : Map.of());
        try {
            return priorityRequestExecutor.executeRequest(userId, () -> chatModel.call(prompt));
        } catch (Exception e) {
            log.warn("SimpleChain: LLM call via PriorityRequestExecutor failed: {}", e.getMessage());
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    private ChatOptions buildOptions(AgentRequest request) {
        String preferredModelId = request.metadata() != null
                ? request.metadata().get(AICommand.PREFERRED_MODEL_ID_FIELD) : null;
        if (preferredModelId == null) {
            return null;
        }
        return ToolCallingChatOptions.builder().model(preferredModelId).build();
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
