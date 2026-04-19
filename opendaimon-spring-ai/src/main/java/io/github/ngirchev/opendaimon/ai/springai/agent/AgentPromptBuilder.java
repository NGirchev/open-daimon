package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentStepResult;

import java.util.List;

/**
 * Builds system and user prompts for the ReAct agent loop.
 *
 * <p>The system prompt instructs the LLM to follow the ReAct pattern:
 * think about what to do, decide on an action (tool call) or provide a final answer.
 * Step history from previous iterations is included so the LLM sees prior
 * thoughts, actions, and observations.
 */
public final class AgentPromptBuilder {

    private AgentPromptBuilder() {
    }

    private static final String REACT_SYSTEM_PROMPT = """
            You are an AI agent that solves tasks step by step using available tools.

            Follow the ReAct pattern:
            1. THINK about what you need to do next
            2. If you need information, call an appropriate tool
            3. After receiving tool results, THINK again about what you learned
            4. Repeat until you can provide a final answer

            Important rules:
            - Use tools when you need external information or capabilities
            - Use web_search for discovery; use fetch_url only for a selected URL that is worth opening
            - If fetch_url returns HTTP error or Error, do not retry the same URL
            - If one site repeatedly blocks fetch_url, switch to another source or answer from search snippets
            - When you have enough information, provide your final answer directly as text
            - Be concise and focused in your reasoning
            - If a tool returns an error, try an alternative approach
            """;

    /**
     * Builds the system prompt including ReAct instructions.
     */
    public static String buildSystemPrompt() {
        return REACT_SYSTEM_PROMPT;
    }

    /**
     * Builds the user message for the current iteration.
     *
     * <p>On the first iteration, this is simply the user's task.
     * On subsequent iterations, it includes the step history
     * so the LLM has context about prior actions and observations.
     */
    public static String buildUserMessage(AgentContext ctx) {
        List<AgentStepResult> history = ctx.getStepHistory();
        if (history.isEmpty()) {
            return ctx.getTask();
        }

        var sb = new StringBuilder();
        sb.append("Original task: ").append(ctx.getTask()).append("\n\n");
        sb.append("Previous steps:\n");

        for (AgentStepResult step : history) {
            sb.append("--- Step ").append(step.iteration() + 1).append(" ---\n");
            if (step.thought() != null) {
                sb.append("Thought: ").append(step.thought()).append('\n');
            }
            if (step.action() != null) {
                sb.append("Action: ").append(step.action());
                if (step.actionInput() != null) {
                    sb.append('(').append(step.actionInput()).append(')');
                }
                sb.append('\n');
            }
            if (step.observation() != null) {
                sb.append("Observation: ").append(step.observation()).append('\n');
            }
            sb.append('\n');
        }

        sb.append("Based on the above steps and observations, continue solving the task. ");
        sb.append("Either call another tool or provide your final answer.");

        return sb.toString();
    }
}
