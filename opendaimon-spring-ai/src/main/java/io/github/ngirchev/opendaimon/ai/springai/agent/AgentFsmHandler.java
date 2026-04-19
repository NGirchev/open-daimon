package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentEvent;

@FunctionalInterface
public interface AgentFsmHandler {
    void handle(AgentContext ctx, AgentEvent event);
}
