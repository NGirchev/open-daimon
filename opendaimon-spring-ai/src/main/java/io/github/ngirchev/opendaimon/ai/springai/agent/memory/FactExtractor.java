package io.github.ngirchev.opendaimon.ai.springai.agent.memory;

import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentStepResult;
import io.github.ngirchev.opendaimon.common.agent.memory.AgentFact;
import io.github.ngirchev.opendaimon.common.agent.memory.AgentMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Extracts key facts from completed agent conversations and stores them in memory.
 *
 * <p>After each agent execution, calls the LLM with the conversation summary
 * and asks it to extract reusable facts. Facts are stored in {@link AgentMemory}
 * so they can be recalled in future agent tasks.
 *
 * <p>Extraction is best-effort — failures are logged but don't affect the
 * agent's response to the user.
 */
@Slf4j
public class FactExtractor {

    private static final String EXTRACTION_PROMPT = """
            Analyze the following agent conversation and extract key facts worth remembering for future interactions.

            Focus on:
            - User preferences and requirements
            - Important domain knowledge discovered
            - Decisions made and their reasoning
            - Technical details that may be relevant later

            Return ONLY a JSON array of strings, each being a concise fact.
            Return empty array [] if nothing is worth remembering.
            Do not include trivial or obvious facts.

            Example: ["User prefers concise technical answers", "Project uses PostgreSQL 17 with Flyway migrations"]
            """;

    private final ChatModel chatModel;
    private final AgentMemory agentMemory;

    public FactExtractor(ChatModel chatModel, AgentMemory agentMemory) {
        this.chatModel = chatModel;
        this.agentMemory = agentMemory;
    }

    /**
     * Extracts facts from a completed agent context and stores them in memory.
     * Best-effort — logs and swallows any errors.
     */
    public void extractAndStore(AgentContext ctx) {
        if (ctx.getConversationId() == null) {
            return;
        }

        try {
            String conversationSummary = buildConversationSummary(ctx);
            List<String> facts = callLlmForFacts(conversationSummary);

            if (facts.isEmpty()) {
                log.debug("FactExtractor: no facts extracted for conversation={}", ctx.getConversationId());
                return;
            }

            for (String factText : facts) {
                AgentFact fact = new AgentFact(
                        UUID.randomUUID().toString(),
                        factText,
                        Map.of("source", "fact_extraction"),
                        Instant.now()
                );
                agentMemory.store(ctx.getConversationId(), fact);
            }

            log.info("FactExtractor: stored {} facts for conversation={}", facts.size(), ctx.getConversationId());
        } catch (Exception e) {
            log.warn("FactExtractor failed: {}", e.getMessage());
        }
    }

    private String buildConversationSummary(AgentContext ctx) {
        var sb = new StringBuilder();
        sb.append("Task: ").append(ctx.getTask()).append("\n\n");

        for (AgentStepResult step : ctx.getStepHistory()) {
            sb.append("Step ").append(step.iteration() + 1).append(":\n");
            if (step.action() != null) {
                sb.append("  Action: ").append(step.action()).append('\n');
            }
            if (step.observation() != null) {
                String obs = step.observation().length() > 500
                        ? step.observation().substring(0, 500) + "..."
                        : step.observation();
                sb.append("  Result: ").append(obs).append('\n');
            }
        }

        if (ctx.getFinalAnswer() != null) {
            String answer = ctx.getFinalAnswer().length() > 1000
                    ? ctx.getFinalAnswer().substring(0, 1000) + "..."
                    : ctx.getFinalAnswer();
            sb.append("\nFinal answer: ").append(answer);
        }

        return sb.toString();
    }

    private List<String> callLlmForFacts(String conversationSummary) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(EXTRACTION_PROMPT),
                new UserMessage(conversationSummary)
        ));

        ChatResponse response = chatModel.call(prompt);
        if (response == null || response.getResult() == null) {
            return List.of();
        }

        String text = response.getResult().getOutput().getText();
        return parseFactsJson(text);
    }

    /**
     * Parses a JSON array of strings from LLM response.
     * Handles common LLM output variations (markdown code blocks, extra whitespace).
     */
    private List<String> parseFactsJson(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // Strip markdown code block if present
        String cleaned = text.strip();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastBlock = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastBlock > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastBlock).strip();
            }
        }

        // Simple JSON array parsing — extract strings between quotes
        if (!cleaned.startsWith("[")) {
            return List.of();
        }

        List<String> facts = new ArrayList<>();
        int i = 0;
        while (i < cleaned.length()) {
            int start = cleaned.indexOf('"', i);
            if (start == -1) break;
            int end = findClosingQuote(cleaned, start + 1);
            if (end == -1) break;
            String fact = cleaned.substring(start + 1, end)
                    .replace("\\\"", "\"")
                    .replace("\\n", " ")
                    .strip();
            if (!fact.isEmpty()) {
                facts.add(fact);
            }
            i = end + 1;
        }

        return facts;
    }

    private int findClosingQuote(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            if (text.charAt(i) == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                return i;
            }
        }
        return -1;
    }
}
