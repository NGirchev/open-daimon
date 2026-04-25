package io.github.ngirchev.opendaimon.common.agent;

import io.github.ngirchev.opendaimon.common.model.Attachment;

import java.util.List;
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
 * @param strategy       execution strategy (AUTO selects based on context)
 * @param attachments    user-provided multimodal attachments (e.g. image attachments) to be
 *                       carried into the first user message of the agent prompt; never null,
 *                       defaults to {@link List#of()} when no attachments are supplied
 */
public record AgentRequest(
        String task,
        String conversationId,
        Map<String, String> metadata,
        int maxIterations,
        Set<String> enabledTools,
        AgentStrategy strategy,
        List<Attachment> attachments
) {

    private static final int DEFAULT_MAX_ITERATIONS = 10;

    /**
     * Compact canonical constructor — normalises {@code null} {@code attachments}
     * to an empty list and defensively copies the input so the record stays immutable.
     */
    public AgentRequest {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public AgentRequest(String task, String conversationId, Map<String, String> metadata) {
        this(task, conversationId, metadata, DEFAULT_MAX_ITERATIONS, Set.of(), AgentStrategy.AUTO, List.of());
    }

    public AgentRequest(String task, String conversationId, Map<String, String> metadata,
                        int maxIterations, Set<String> enabledTools) {
        this(task, conversationId, metadata, maxIterations, enabledTools, AgentStrategy.AUTO, List.of());
    }

    public AgentRequest(String task, String conversationId, Map<String, String> metadata,
                        int maxIterations, Set<String> enabledTools, AgentStrategy strategy) {
        this(task, conversationId, metadata, maxIterations, enabledTools, strategy, List.of());
    }
}
