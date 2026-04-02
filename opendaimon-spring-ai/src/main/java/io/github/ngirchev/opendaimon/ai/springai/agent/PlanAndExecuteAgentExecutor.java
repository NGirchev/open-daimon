package io.github.ngirchev.opendaimon.ai.springai.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentResult;
import io.github.ngirchev.opendaimon.common.agent.AgentState;
import io.github.ngirchev.opendaimon.common.agent.AgentStepResult;
import io.github.ngirchev.opendaimon.common.agent.AgentStrategy;
import lombok.extern.slf4j.Slf4j;
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
 * Plan-and-Execute agent executor.
 *
 * <p>First asks the LLM to generate a step-by-step plan, then executes
 * each step using the ReAct executor. Results from previous steps are
 * passed as context to subsequent steps.
 */
@Slf4j
public class PlanAndExecuteAgentExecutor implements AgentExecutor {

    private static final String PLANNING_PROMPT = """
            You are a planning agent. Given a complex task, break it down into 2-5 concrete steps.

            Rules:
            - Each step should be a self-contained sub-task
            - Steps should be in execution order
            - Each step should be achievable with available tools (web search, HTTP requests)
            - Return ONLY a JSON array of step descriptions as strings
            - Do not include meta-steps like "plan" or "finalize"

            Example: ["Search for current Bitcoin price", "Search for Bitcoin price one week ago", "Calculate the percentage change and explain the trend"]
            """;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final ChatModel chatModel;
    private final AgentExecutor reactExecutor;

    public PlanAndExecuteAgentExecutor(ChatModel chatModel, AgentExecutor reactExecutor) {
        this.chatModel = chatModel;
        this.reactExecutor = reactExecutor;
    }

    @Override
    public AgentResult execute(AgentRequest request) {
        Instant start = Instant.now();
        log.info("PlanAndExecute started: task='{}'", request.task());

        try {
            List<String> plan = generatePlan(request.task());
            if (plan.isEmpty()) {
                log.warn("PlanAndExecute: empty plan, falling back to ReAct");
                return reactExecutor.execute(request);
            }

            log.info("PlanAndExecute: generated {} steps", plan.size());

            List<AgentStepResult> allSteps = new ArrayList<>();
            StringBuilder accumulatedContext = new StringBuilder();
            String lastAnswer = null;
            int totalIterations = 0;

            for (int i = 0; i < plan.size(); i++) {
                String stepTask = plan.get(i);
                String enrichedTask = accumulatedContext.isEmpty()
                        ? stepTask
                        : stepTask + "\n\nContext from previous steps:\n" + accumulatedContext;

                int stepMaxIterations = Math.max(3, request.maxIterations() / plan.size());

                AgentRequest stepRequest = new AgentRequest(
                        enrichedTask,
                        request.conversationId(),
                        request.metadata(),
                        stepMaxIterations,
                        request.enabledTools(),
                        AgentStrategy.REACT
                );

                log.info("PlanAndExecute step {}/{}: '{}'", i + 1, plan.size(), stepTask);
                AgentResult stepResult = reactExecutor.execute(stepRequest);

                allSteps.addAll(stepResult.steps());
                totalIterations += stepResult.iterationsUsed();

                if (stepResult.isSuccess() && stepResult.finalAnswer() != null) {
                    lastAnswer = stepResult.finalAnswer();
                    accumulatedContext.append("Step ").append(i + 1).append(": ").append(stepTask)
                            .append("\nResult: ").append(lastAnswer).append("\n\n");
                } else {
                    log.warn("PlanAndExecute step {} failed: {}", i + 1, stepResult.terminalState());
                    Duration duration = Duration.between(start, Instant.now());
                    return new AgentResult(lastAnswer, allSteps, AgentState.FAILED, totalIterations, duration);
                }
            }

            Duration duration = Duration.between(start, Instant.now());
            log.info("PlanAndExecute completed: {} steps, {} iterations, {}ms",
                    plan.size(), totalIterations, duration.toMillis());

            return new AgentResult(lastAnswer, allSteps, AgentState.COMPLETED, totalIterations, duration);

        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            log.error("PlanAndExecute failed: {}", e.getMessage(), e);
            return new AgentResult(null, List.of(), AgentState.FAILED, 0, duration);
        }
    }

    private List<String> generatePlan(String task) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(PLANNING_PROMPT),
                new UserMessage(task)
        ));

        ChatResponse response = chatModel.call(prompt);
        response.getResult();

        String text = response.getResult().getOutput().getText();
        return parsePlanJson(text);
    }

    private List<String> parsePlanJson(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String cleaned = text.strip();
        // Strip markdown code fences if present
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastBlock = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastBlock > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastBlock).strip();
            }
        }

        if (!cleaned.startsWith("[")) {
            return List.of();
        }

        try {
            List<String> steps = OBJECT_MAPPER.readValue(cleaned, STRING_LIST_TYPE);
            return steps.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(String::strip)
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to parse plan JSON, falling back to ReAct: {}", e.getMessage());
            return List.of();
        }
    }
}
