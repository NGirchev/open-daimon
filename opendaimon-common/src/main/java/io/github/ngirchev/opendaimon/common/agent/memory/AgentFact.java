package io.github.ngirchev.opendaimon.common.agent.memory;

import java.time.Instant;
import java.util.Map;

/**
 * A single fact stored in agent memory.
 *
 * @param id        unique identifier for this fact
 * @param content   the fact text (e.g., "User prefers concise answers")
 * @param metadata  additional context (e.g., source, category, confidence)
 * @param createdAt when this fact was stored
 */
public record AgentFact(
        String id,
        String content,
        Map<String, String> metadata,
        Instant createdAt
) {

    public AgentFact(String id, String content) {
        this(id, content, Map.of(), Instant.now());
    }
}
