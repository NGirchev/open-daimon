package io.github.ngirchev.opendaimon.common.agent;

import io.github.ngirchev.opendaimon.common.command.IChatCommand;
import io.github.ngirchev.opendaimon.common.model.Attachment;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chat command that triggers agent-mode processing.
 *
 * <p>When this command reaches the {@link AgentCommandHandler}, it is executed
 * via {@link AgentExecutor} instead of the regular AI pipeline.
 *
 * @param userId         user who sent the command
 * @param userText       task description for the agent
 * @param stream         whether to stream the response (currently sync only)
 * @param conversationId conversation thread identifier
 * @param metadata       additional context (channel-specific info)
 * @param maxIterations  override for agent loop iterations (null = use default)
 * @param enabledTools   tool names to make available (empty = all)
 * @param attachments    file attachments (passed to agent context)
 */
public record AgentChatCommand(
        Long userId,
        String userText,
        boolean stream,
        String conversationId,
        Map<String, String> metadata,
        Integer maxIterations,
        Set<String> enabledTools,
        List<Attachment> attachments
) implements IChatCommand<AgentCommandType> {

    @Override
    public AgentCommandType commandType() {
        return AgentCommandType.AGENT;
    }

    @Override
    public List<Attachment> attachments() {
        return attachments != null ? attachments : List.of();
    }

    public AgentChatCommand(Long userId, String userText, String conversationId, Map<String, String> metadata) {
        this(userId, userText, false, conversationId, metadata, null, Set.of(), List.of());
    }
}
