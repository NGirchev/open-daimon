package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentLoopActions;
import io.github.ngirchev.opendaimon.common.agent.AgentStepResult;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.common.agent.AgentToolResult;
import io.github.ngirchev.opendaimon.common.agent.memory.AgentFact;
import io.github.ngirchev.opendaimon.common.agent.memory.AgentMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
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

    /**
     * Conversation history maintained across iterations within a single agent execution.
     * Reset per execution — not shared across different AgentRequest invocations.
     */
    private final ThreadLocal<List<Message>> conversationHistory = ThreadLocal.withInitial(ArrayList::new);
    private final ThreadLocal<Prompt> lastPrompt = new ThreadLocal<>();
    private final ThreadLocal<ChatResponse> lastResponse = new ThreadLocal<>();

    public SpringAgentLoopActions(ChatModel chatModel,
                                  ToolCallingManager toolCallingManager,
                                  List<ToolCallback> toolCallbacks,
                                  AgentMemory agentMemory) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.toolCallbacks = toolCallbacks != null ? List.copyOf(toolCallbacks) : List.of();
        this.agentMemory = agentMemory;
    }

    @Override
    public void think(AgentContext ctx) {
        ctx.emitEvent(AgentStreamEvent.thinking(ctx.getCurrentIteration()));
        try {
            List<Message> messages = conversationHistory.get();

            if (messages.isEmpty()) {
                String systemPrompt = AgentPromptBuilder.buildSystemPrompt();
                String memoryContext = recallMemoryContext(ctx);
                if (memoryContext != null) {
                    systemPrompt = systemPrompt + "\n\n" + memoryContext;
                }
                messages.add(new SystemMessage(systemPrompt));
                messages.add(new UserMessage(AgentPromptBuilder.buildUserMessage(ctx)));
            }

            ToolCallingChatOptions chatOptions = ToolCallingChatOptions.builder()
                    .toolCallbacks(toolCallbacks)
                    .internalToolExecutionEnabled(false)
                    .build();

            Prompt prompt = new Prompt(List.copyOf(messages), chatOptions);
            lastPrompt.set(prompt);

            log.info("Agent think: iteration={}, messages={}, tools={}",
                    ctx.getCurrentIteration(), messages.size(), toolCallbacks.size());

            ChatResponse response = chatModel.call(prompt);
            lastResponse.set(response);

            if (response == null || response.getResult() == null) {
                ctx.setErrorMessage("LLM returned empty response");
                return;
            }

            var output = response.getResult().getOutput();

            if (response.hasToolCalls()) {
                var toolCalls = output.getToolCalls();
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
            Prompt prompt = lastPrompt.get();
            ChatResponse response = lastResponse.get();

            if (prompt == null || response == null) {
                ctx.setErrorMessage("No prompt/response available for tool execution");
                return;
            }

            log.info("Agent executeTool: tool={}", ctx.getCurrentToolName());

            ToolExecutionResult toolResult = toolCallingManager.executeToolCalls(prompt, response);

            List<Message> resultMessages = toolResult.conversationHistory();
            String observation = extractToolObservation(resultMessages);

            ctx.setToolResult(AgentToolResult.success(ctx.getCurrentToolName(), observation));

            List<Message> messages = conversationHistory.get();
            if (!resultMessages.isEmpty()) {
                Message lastMsg = resultMessages.getLast();
                messages.add(lastMsg);
            }

            messages.add(new UserMessage(AgentPromptBuilder.buildUserMessage(ctx)));

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
        AgentToolResult toolResultVal = ctx.getToolResult();
        String obs = toolResultVal != null && toolResultVal.success()
                ? toolResultVal.result()
                : (toolResultVal != null ? "Error: " + toolResultVal.error() : null);
        ctx.emitEvent(AgentStreamEvent.observation(obs, ctx.getCurrentIteration()));

        AgentToolResult toolResult = ctx.getToolResult();
        String observation = toolResult != null && toolResult.success()
                ? toolResult.result()
                : (toolResult != null ? "Error: " + toolResult.error() : "No result");

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
        cleanup();
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
        cleanup();
        log.warn("Agent handleMaxIterations: {} iterations exhausted", ctx.getMaxIterations());
    }

    @Override
    public void handleError(AgentContext ctx) {
        cleanup();
        log.error("Agent handleError: {}", ctx.getErrorMessage());
    }

    /**
     * Recalls relevant facts from agent memory and formats them as context.
     * Returns null if memory is not configured or no facts are found.
     */
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
     * Extracts the tool result text from the conversation history returned by
     * {@link ToolCallingManager#executeToolCalls}.
     */
    private String extractToolObservation(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        Message last = messages.getLast();
        return last.getText();
    }

    private void cleanup() {
        conversationHistory.remove();
        lastPrompt.remove();
        lastResponse.remove();
    }
}
