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

    private static final int MAX_STEP_ACTION_INPUT_LENGTH = 600;
    private static final int MAX_STEP_OBSERVATION_LENGTH = 1200;

    private static final String REACT_SYSTEM_PROMPT = """
            You are an AI agent that solves tasks step by step using available tools.

            Follow the ReAct pattern:
            1. THINK about what you need to do next
            2. If you need information, call an appropriate tool
            3. After receiving tool results, THINK again about what you learned
            4. Repeat until you can provide a final answer

            Important rules:
            - Use tools when you need external information or capabilities
            - When you have enough information, provide your final answer directly as text
            - Be concise and focused in your reasoning
            - If a tool returns an error, try an alternative approach
            - NEVER fabricate URLs, document IDs, file paths, or citation anchors. Do not write a link you did not receive verbatim from a tool call in this conversation.
            - If the user asks for a source, reference, or link and you did not fetch it via web_search/fetch_url/http_get in the current conversation — say so honestly ("I don't have a verified source for that") instead of producing a plausible-looking URL from memory.
            - When you DO cite a URL, copy it byte-for-byte from the most recent matching tool result. Do not trim, shorten, guess slugs, or "fix" them.
            - Domain knowledge (e.g. "Quarkus has a performance guide") is fine to mention in prose, but without a concrete URL unless a tool returned one.
            """;

    private static final String MAX_ITERATIONS_SYNTHESIS_SYSTEM_PROMPT = """
            You are preparing the final user answer after an agent loop reached its iteration limit.

            Important rules:
            - Use only the provided collected steps and observations
            - Do not call tools and do not output tool-call syntax
            - Do not output debug logs, JSON dumps, or internal reasoning traces
            - Be transparent when data is incomplete, but still provide the best possible answer
            - Keep the answer concise and useful for the end user
            """;

    /**
     * Builds the system prompt including ReAct instructions.
     */
    public static String buildSystemPrompt() {
        return REACT_SYSTEM_PROMPT;
    }

    /**
     * Builds a dedicated system prompt for the final synthesis pass when
     * the agent reached max iterations.
     */
    public static String buildMaxIterationsSynthesisSystemPrompt() {
        return MAX_ITERATIONS_SYNTHESIS_SYSTEM_PROMPT;
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

    /**
     * Builds the user message for the final synthesis pass after the
     * iteration budget is exhausted.
     */
    public static String buildMaxIterationsSynthesisUserMessage(AgentContext ctx) {
        List<AgentStepResult> history = ctx.getStepHistory();
        var sb = new StringBuilder();
        sb.append("Original task: ").append(ctx.getTask()).append("\n\n");
        sb.append("Iteration limit reached: ").append(ctx.getMaxIterations()).append("\n\n");

        if (history.isEmpty()) {
            sb.append("Collected steps: none.\n\n");
        } else {
            sb.append("Collected steps:\n");
            for (AgentStepResult step : history) {
                sb.append("--- Step ").append(step.iteration() + 1).append(" ---\n");
                if (step.action() != null) {
                    sb.append("Action: ").append(step.action()).append('\n');
                }
                if (step.actionInput() != null) {
                    sb.append("Action input: ").append(truncate(step.actionInput(), MAX_STEP_ACTION_INPUT_LENGTH)).append('\n');
                }
                if (step.observation() != null) {
                    sb.append("Observation: ").append(truncate(step.observation(), MAX_STEP_OBSERVATION_LENGTH)).append('\n');
                }
                sb.append('\n');
            }
        }

        sb.append("Using only the collected information above, write the final answer for the user. ");
        sb.append("Do not include tool payload markers, raw logs, or JSON dumps.");
        return sb.toString();
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
