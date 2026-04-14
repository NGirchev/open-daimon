package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.ai.springai.agent.memory.FactExtractor;
import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentLoopActions;
import io.github.ngirchev.opendaimon.common.agent.AgentStepResult;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.common.agent.AgentToolResult;
import io.github.ngirchev.opendaimon.common.agent.memory.AgentFact;
import io.github.ngirchev.opendaimon.common.agent.memory.AgentMemory;
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
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spring AI implementation of the agent loop actions.
 *
 * <p>Uses {@link ChatModel} with {@code internalToolExecutionEnabled=false}
 * to get manual control over tool calling. This allows the FSM to manage
 * each ReAct iteration explicitly rather than letting Spring AI auto-execute tools.
 *
 * <p>Tool execution is delegated to {@link ToolCallingManager} which resolves
 * and invokes tools discovered by Spring AI's {@code SpringBeanToolCallbackResolver}.
 */
@Slf4j
public class SpringAgentLoopActions implements AgentLoopActions {

    private static final int MEMORY_RECALL_TOP_K = 5;

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final List<ToolCallback> toolCallbacks;
    private final AgentMemory agentMemory;
    private final FactExtractor factExtractor;
    private final ChatMemory chatMemory;

    private static final String KEY_CONVERSATION_HISTORY = "spring.conversationHistory";
    private static final String KEY_LAST_PROMPT = "spring.lastPrompt";
    private static final String KEY_LAST_RESPONSE = "spring.lastResponse";

    public SpringAgentLoopActions(ChatModel chatModel,
                                  ToolCallingManager toolCallingManager,
                                  List<ToolCallback> toolCallbacks,
                                  AgentMemory agentMemory,
                                  FactExtractor factExtractor,
                                  ChatMemory chatMemory) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.toolCallbacks = toolCallbacks != null ? List.copyOf(toolCallbacks) : List.of();
        this.agentMemory = agentMemory;
        this.factExtractor = factExtractor;
        this.chatMemory = chatMemory;
    }

    @Override
    public void think(AgentContext ctx) {
        ctx.emitEvent(AgentStreamEvent.thinking(ctx.getCurrentIteration()));
        try {
            List<Message> messages = getOrCreateHistory(ctx);

            if (messages.isEmpty()) {
                String systemPrompt = AgentPromptBuilder.buildSystemPrompt();
                String memoryContext = recallMemoryContext(ctx);
                if (memoryContext != null) {
                    systemPrompt = systemPrompt + "\n\n" + memoryContext;
                }
                messages.add(new SystemMessage(systemPrompt));
                loadConversationHistory(ctx, messages);
                messages.add(new UserMessage(AgentPromptBuilder.buildUserMessage(ctx)));
            }

            List<ToolCallback> effectiveCallbacks = resolveEffectiveTools(ctx);
            ToolCallingChatOptions chatOptions = ToolCallingChatOptions.builder()
                    .toolCallbacks(effectiveCallbacks)
                    .internalToolExecutionEnabled(false)
                    .build();

            Prompt prompt = new Prompt(List.copyOf(messages), chatOptions);
            ctx.putExtra(KEY_LAST_PROMPT, prompt);

            log.info("Agent think: iteration={}, messages={}, tools={}",
                    ctx.getCurrentIteration(), messages.size(), toolCallbacks.size());

            ChatResponse response = chatModel.call(prompt);
            ctx.putExtra(KEY_LAST_RESPONSE, response);

            if (response.getMetadata() != null && response.getMetadata().getModel() != null) {
                ctx.setModelName(response.getMetadata().getModel());
            }

            response.getResult();

            var output = response.getResult().getOutput();

            if (response.hasToolCalls()) {
                var toolCalls = output.getToolCalls();
                if (toolCalls.size() > 1) {
                    log.warn("Agent think: LLM returned {} tool calls, only the first will be executed. " +
                            "Parallel tool calls are not yet supported.", toolCalls.size());
                }
                var firstToolCall = toolCalls.getFirst();
                ctx.setCurrentThought("Calling tool: " + firstToolCall.name());
                ctx.setCurrentToolName(firstToolCall.name());
                ctx.setCurrentToolArguments(firstToolCall.arguments());
                log.info("Agent think: tool call detected — tool={}, args={}",
                        firstToolCall.name(), firstToolCall.arguments());
            } else {
                String text = output.getText();
                ctx.setCurrentThought("Final answer ready");
                ctx.setCurrentTextResponse(text);
                log.info("Agent think: final answer, length={}",
                        text != null ? text.length() : 0);
            }

            messages.add(output);

        } catch (Exception e) {
            log.error("Agent think failed: {}", e.getMessage(), e);
            ctx.setErrorMessage("LLM call failed: " + e.getMessage());
        }
    }

    @Override
    public void executeTool(AgentContext ctx) {
        ctx.emitEvent(AgentStreamEvent.toolCall(
                ctx.getCurrentToolName(), ctx.getCurrentToolArguments(), ctx.getCurrentIteration()));
        try {
            Prompt prompt = ctx.getExtra(KEY_LAST_PROMPT);
            ChatResponse response = ctx.getExtra(KEY_LAST_RESPONSE);

            if (prompt == null || response == null) {
                ctx.setErrorMessage("No prompt/response available for tool execution");
                return;
            }

            log.info("Agent executeTool: tool={}", ctx.getCurrentToolName());

            ToolExecutionResult toolResult = toolCallingManager.executeToolCalls(prompt, response);

            List<Message> resultMessages = toolResult.conversationHistory();
            String observation = extractToolObservation(resultMessages);

            ctx.setToolResult(AgentToolResult.success(ctx.getCurrentToolName(), observation));

            List<Message> messages = getOrCreateHistory(ctx);
            if (!resultMessages.isEmpty()) {
                Message lastMsg = resultMessages.getLast();
                messages.add(lastMsg);
            }

            log.info("Agent executeTool: completed, observation length={}",
                    observation != null ? observation.length() : 0);

        } catch (Exception e) {
            log.error("Agent executeTool failed: tool={}, error={}",
                    ctx.getCurrentToolName(), e.getMessage(), e);
            ctx.setToolResult(AgentToolResult.failure(ctx.getCurrentToolName(), e.getMessage()));
        }
    }

    @Override
    public void observe(AgentContext ctx) {
        AgentToolResult toolResult = ctx.getToolResult();
        String observation = toolResult != null && toolResult.success()
                ? toolResult.result()
                : (toolResult != null ? "Error: " + toolResult.error() : "No result");
        ctx.emitEvent(AgentStreamEvent.observation(observation, ctx.getCurrentIteration()));

        ctx.recordStep(new AgentStepResult(
                ctx.getCurrentIteration(),
                ctx.getCurrentThought(),
                ctx.getCurrentToolName(),
                ctx.getCurrentToolArguments(),
                observation,
                Instant.now()
        ));

        ctx.incrementIteration();
        ctx.resetIterationState();

        log.info("Agent observe: iteration={} recorded, moving to next think cycle",
                ctx.getCurrentIteration());
    }

    @Override
    public void answer(AgentContext ctx) {
        ctx.setFinalAnswer(ctx.getCurrentTextResponse());
        saveConversationHistory(ctx);
        extractFacts(ctx);
        cleanup(ctx);
        log.info("Agent answer: final answer set, length={}",
                ctx.getFinalAnswer() != null ? ctx.getFinalAnswer().length() : 0);
    }

    @Override
    public void handleMaxIterations(AgentContext ctx) {
        List<AgentStepResult> history = ctx.getStepHistory();
        var sb = new StringBuilder();
        sb.append("I reached the maximum number of iterations (").append(ctx.getMaxIterations()).append("). ");
        sb.append("Here is what I found so far:\n\n");

        for (AgentStepResult step : history) {
            if (step.observation() != null) {
                sb.append("- ").append(step.action()).append(": ").append(
                        step.observation().length() > 200
                                ? step.observation().substring(0, 200) + "..."
                                : step.observation()
                ).append('\n');
            }
        }

        ctx.setFinalAnswer(sb.toString());
        cleanup(ctx);
        log.warn("Agent handleMaxIterations: {} iterations exhausted", ctx.getMaxIterations());
    }

    @Override
    public void handleError(AgentContext ctx) {
        if (ctx.getErrorMessage() == null) {
            ctx.setErrorMessage("LLM returned neither a tool call nor a final answer");
        }
        cleanup(ctx);
        log.error("Agent handleError: {}", ctx.getErrorMessage());
    }

    /**
     * Extracts key facts from the completed conversation and stores them in memory.
     * Best-effort — failures don't affect the agent response.
     */
    private void extractFacts(AgentContext ctx) {
        if (factExtractor != null && !ctx.getStepHistory().isEmpty()) {
            // Only extract when there were tool interactions (non-trivial conversations)
            factExtractor.extractAndStore(ctx);
        }
    }

    private String recallMemoryContext(AgentContext ctx) {
        if (agentMemory == null || ctx.getConversationId() == null) {
            return null;
        }
        try {
            List<AgentFact> facts = agentMemory.recall(
                    ctx.getConversationId(), ctx.getTask(), MEMORY_RECALL_TOP_K);
            if (facts.isEmpty()) {
                return null;
            }
            String factsText = facts.stream()
                    .map(AgentFact::content)
                    .collect(Collectors.joining("\n- ", "- ", ""));
            log.info("Agent memory: recalled {} facts for task", facts.size());
            return "Relevant information from memory:\n" + factsText;
        } catch (Exception e) {
            log.warn("Agent memory recall failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Filters the full tool callback list by {@code ctx.getEnabledTools()}.
     * If enabledTools is empty or null, all tools are available (default behavior).
     */
    private List<ToolCallback> resolveEffectiveTools(AgentContext ctx) {
        Set<String> enabled = ctx.getEnabledTools();
        if (enabled == null || enabled.isEmpty()) {
            return toolCallbacks;
        }
        List<ToolCallback> filtered = toolCallbacks.stream()
                .filter(cb -> enabled.contains(cb.getToolDefinition().name()))
                .toList();
        if (filtered.isEmpty()) {
            log.warn("Agent think: enabledTools={} matched no registered tools, using all", enabled);
            return toolCallbacks;
        }
        return filtered;
    }

    /**
     * Extracts the tool result text from the conversation history returned by
     * {@link ToolCallingManager#executeToolCalls}.
     */
    private String extractToolObservation(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "(no tool output)";
        }
        String text = messages.getLast().getText();
        return text != null ? text : "(no tool output)";
    }

    private void cleanup(AgentContext ctx) {
        ctx.removeExtra(KEY_CONVERSATION_HISTORY);
        ctx.removeExtra(KEY_LAST_PROMPT);
        ctx.removeExtra(KEY_LAST_RESPONSE);
    }

    /**
     * Loads prior conversation turns from {@link ChatMemory} and appends them
     * between the system prompt and the current user message. Skips any
     * {@link SystemMessage} entries from memory (e.g. summaries) to avoid
     * conflicting with the agent system prompt — the summary content is
     * prepended to the first system message instead.
     */
    private void loadConversationHistory(AgentContext ctx, List<Message> messages) {
        if (chatMemory == null || ctx.getConversationId() == null) {
            return;
        }
        try {
            List<Message> history = chatMemory.get(ctx.getConversationId());
            if (history == null || history.isEmpty()) {
                return;
            }
            for (Message msg : history) {
                if (msg.getMessageType() == MessageType.SYSTEM) {
                    // Append summary to existing system prompt instead of adding a second SystemMessage
                    if (!messages.isEmpty() && messages.getFirst() instanceof SystemMessage existing) {
                        messages.set(0, new SystemMessage(existing.getText() + "\n\n" + msg.getText()));
                    }
                } else {
                    messages.add(msg);
                }
            }
            log.info("Agent think: loaded {} history messages from ChatMemory", history.size());
        } catch (Exception e) {
            log.warn("Agent think: failed to load conversation history: {}", e.getMessage());
        }
    }

    /**
     * Persists the current user message and final assistant answer to
     * {@link ChatMemory} so they are available in subsequent turns.
     */
    private void saveConversationHistory(AgentContext ctx) {
        if (chatMemory == null || ctx.getConversationId() == null) {
            return;
        }
        try {
            String conversationId = ctx.getConversationId();
            chatMemory.add(conversationId, List.of(
                    new UserMessage(ctx.getTask()),
                    new AssistantMessage(ctx.getCurrentTextResponse())
            ));
            log.info("Agent answer: saved user+assistant messages to ChatMemory");
        } catch (Exception e) {
            log.warn("Agent answer: failed to save conversation history: {}", e.getMessage());
        }
    }

    private List<Message> getOrCreateHistory(AgentContext ctx) {
        List<Message> history = ctx.getExtra(KEY_CONVERSATION_HISTORY);
        if (history == null) {
            history = new ArrayList<>();
            ctx.putExtra(KEY_CONVERSATION_HISTORY, history);
        }
        return history;
    }
}
