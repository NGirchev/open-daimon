package io.github.ngirchev.opendaimon.common.agent;

import java.util.Map;
import java.util.Set;

/**
 * Immutable input for agent execution.
 *
 * @param task           natural language task description for the agent
 * @param conversationId conversation thread identifier for memory/history
 * @param metadata       additional context (e.g., user ID, channel info)
 * @param maxIterations  safety limit for ReAct loop iterations
 * @param enabledTools   tool names to make available (empty = all discovered tools)
 */
public record AgentRequest(
        String task,
        String conversationId,
        Map<String, String> metadata,
        int maxIterations,
        Set<String> enabledTools
) {

    private static final int DEFAULT_MAX_ITERATIONS = 10;

    public AgentRequest(String task, String conversationId, Map<String, String> metadata) {
        this(task, conversationId, metadata, DEFAULT_MAX_ITERATIONS, Set.of());
    }
}
