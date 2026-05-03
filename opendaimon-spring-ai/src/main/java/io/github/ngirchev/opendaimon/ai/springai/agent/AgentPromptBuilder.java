package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentStepResult;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.lang.LanguageInstructions;

import java.util.List;
import java.util.Map;

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

    private static final String TOOL_CALLING_INSTRUCTION =
            "\nWhen calling any tool, you MUST provide all required parameters"
            + " with concrete non-empty values. Never emit a tool call with empty"
            + " or null arguments. For web_search, always include a non-empty"
            + " `query` string describing what to search. For fetch_url, always"
            + " include a valid http(s) `url`.";

    /**
     * Builds the system prompt enriched with language and tool-calling instructions
     * derived from agent metadata.
     *
     * <p>The tool-calling discipline instruction is appended unconditionally because
     * the ReAct agent always operates with web_search/fetch_url tools available.
     * The language instruction is appended only when {@link AICommand#LANGUAGE_CODE_FIELD}
     * is present in the metadata — it covers intermediate thoughts and status messages
     * as well as the final answer to eliminate bifurcated-language output.
     *
     * @param metadata agent metadata from {@link AgentContext#getMetadata()}, may be {@code null}
     */
    public static String buildSystemPrompt(Map<String, String> metadata) {
        String prompt = REACT_SYSTEM_PROMPT + TOOL_CALLING_INSTRUCTION;
        return appendLanguageInstruction(prompt, metadata);
    }

    private static String appendLanguageInstruction(String prompt, Map<String, String> metadata) {
        if (metadata == null) return prompt;
        String code = metadata.get(AICommand.LANGUAGE_CODE_FIELD);
        return LanguageInstructions.displayName(code)
                .map(name -> prompt
                        + "\nRespond in " + name + " (" + code + "), INCLUDING intermediate thoughts and status messages."
                        + " When quoting text from documents or tool results, preserve the original language exactly.")
                .orElse(prompt);
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
