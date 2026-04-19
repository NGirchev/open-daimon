package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentStepResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Produces the MAX_ITERATIONS closing answer.
 *
 * <p>Primary path ({@link #callSummaryModelWithoutTools}): re-invokes the chat model with an
 * explicit "no more tools" system prompt, feeds the observation log as context, and returns
 * a direct answer in the user's language. Falls back to {@link #buildFallbackSummary} — a
 * deterministic step-history digest — when the LLM call fails or returns empty content, so
 * the user never receives a blank message on iteration exhaustion.
 *
 * <p>The language instruction is derived from the {@code languageCode} entry in
 * {@link AgentContext#getMetadata()} (key {@link AICommand#LANGUAGE_CODE_FIELD}) — the same
 * metadata field used elsewhere in the agent pipeline for localisation.
 */
@Slf4j
final class SummaryModelInvoker {

    private final ChatModel chatModel;
    private final PriorityRequestExecutor priorityRequestExecutor;

    SummaryModelInvoker(ChatModel chatModel, PriorityRequestExecutor priorityRequestExecutor) {
        this.chatModel = chatModel;
        this.priorityRequestExecutor = priorityRequestExecutor;
    }

    /**
     * Asks the chat model for a direct answer with tools disabled. Throws if the model
     * returns empty content so the caller can fall back to {@link #buildFallbackSummary}.
     */
    String callSummaryModelWithoutTools(AgentContext ctx) {
        List<Message> messages = new ArrayList<>();
        String langInstruction = resolveLanguageInstruction(ctx.getMetadata());
        String systemPrompt = "You have reached the iteration limit. "
                + "Based on the step history, give a direct answer to the user's original question. "
                + "Do not call any tools. "
                + "Do not explain the research process. "
                + "Do not use introductory phrases like 'Based on', 'Answer:', 'According to', "
                + "'The searches showed', or similar. "
                + "If the available information is insufficient, say so in one sentence."
                + (langInstruction.isEmpty() ? "" : "\n" + langInstruction);
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(ctx.getTask() + "\n\nContext so far:\n" + flattenStepHistory(ctx)));

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .toolCallbacks(List.of())
                .build();

        Long userId = resolveUserId(ctx.getMetadata());
        ChatResponse response;
        if (priorityRequestExecutor == null) {
            response = chatModel.call(new Prompt(messages, options));
        } else {
            try {
                response = priorityRequestExecutor.executeRequest(userId,
                        () -> chatModel.call(new Prompt(messages, options)));
            } catch (Exception e) {
                throw new RuntimeException("Summary LLM call failed via PriorityRequestExecutor", e);
            }
        }
        String raw = response.getResult() != null && response.getResult().getOutput() != null
                ? response.getResult().getOutput().getText()
                : null;
        String clean = AgentTextSanitizer.stripToolCallTags(AgentTextSanitizer.stripThinkTags(raw));
        if (clean == null || clean.isBlank()) {
            throw new IllegalStateException("Summary LLM returned empty content");
        }
        return clean;
    }

    /**
     * Deterministic StringBuilder digest of step history — used when the summary LLM call
     * throws (network, rate limit, empty content). Guarantees a non-empty final answer on
     * MAX_ITERATIONS so the UI always has something to render.
     */
    String buildFallbackSummary(AgentContext ctx) {
        var sb = new StringBuilder();
        sb.append("I reached the maximum number of iterations (").append(ctx.getMaxIterations()).append("). ");
        sb.append("Here is what I found so far:\n\n");
        for (AgentStepResult step : ctx.getStepHistory()) {
            if (step.observation() != null) {
                String obs = AgentTextSanitizer.stripToolCallTags(step.observation());
                sb.append("- ").append(step.action()).append(": ").append(
                        obs != null && obs.length() > 200 ? obs.substring(0, 200) + "..." : (obs != null ? obs : "")
                ).append('\n');
            }
        }
        return sb.toString();
    }

    /** Flattens step history into a plain-text block for the summary prompt. */
    private static String flattenStepHistory(AgentContext ctx) {
        var sb = new StringBuilder();
        for (AgentStepResult step : ctx.getStepHistory()) {
            if (step.observation() != null) {
                String obs = AgentTextSanitizer.stripToolCallTags(step.observation());
                sb.append("- ").append(step.action()).append(": ").append(
                        obs != null && obs.length() > 500 ? obs.substring(0, 500) + "..." : (obs != null ? obs : "")
                ).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Extracts the numeric user ID from agent metadata. Returns {@code null} when
     * the field is absent or cannot be parsed — {@code NoOpPriorityRequestExecutor}
     * accepts {@code null} and runs without bulkhead.
     */
    static Long resolveUserId(Map<String, String> metadata) {
        if (metadata == null) {
            return null;
        }
        String raw = metadata.get(AICommand.USER_ID_FIELD);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("SummaryModelInvoker: unparseable userId='{}', falling back to null", raw);
            return null;
        }
    }

    /**
     * Resolves a language instruction string from agent metadata.
     * Returns an empty string if no {@code languageCode} is set in metadata.
     */
    private static String resolveLanguageInstruction(Map<String, String> metadata) {
        if (metadata == null) return "";
        String code = metadata.get(AICommand.LANGUAGE_CODE_FIELD);
        if (code == null || code.isBlank()) return "";
        String name = switch (code.toLowerCase()) {
            case "ru" -> "Russian";
            case "en" -> "English";
            case "de" -> "German";
            case "fr" -> "French";
            case "es" -> "Spanish";
            case "zh" -> "Chinese";
            default -> code;
        };
        return "Respond in " + name + " (" + code + ").";
    }
}
