package io.github.ngirchev.opendaimon.common.agent;

import io.github.ngirchev.opendaimon.common.command.ICommandType;

/**
 * Command type marker for agent-mode requests.
 *
 * <p>Used by {@link AgentChatCommand} to signal that a user message
 * should be processed by the agent loop rather than a regular chat handler.
 */
public enum AgentCommandType implements ICommandType {

    /** User invoked agent mode (e.g., via /agent command or agent toggle). */
    AGENT
}
