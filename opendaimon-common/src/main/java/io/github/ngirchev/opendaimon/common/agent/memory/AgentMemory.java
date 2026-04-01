package io.github.ngirchev.opendaimon.common.agent.memory;

import java.util.List;

/**
 * SPI for pluggable agent memory.
 *
 * <p>Allows the agent to store facts learned during conversations and recall
 * relevant information when processing new tasks. This goes beyond chat history
 * (which is handled by Spring AI's {@code ChatMemory}) — agent memory captures
 * extracted knowledge, user preferences, and domain facts.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@code SemanticAgentMemory} — VectorStore-backed semantic search</li>
 *   <li>{@code CompositeAgentMemory} — combines multiple memory sources</li>
 * </ul>
 */
public interface AgentMemory {

    /**
     * Stores a fact associated with a conversation or user.
     *
     * @param conversationId conversation or user scope for this fact
     * @param fact           the fact to store
     */
    void store(String conversationId, AgentFact fact);

    /**
     * Recalls facts relevant to the given query using semantic similarity.
     *
     * @param conversationId conversation or user scope to search within
     * @param query          natural language query to match against stored facts
     * @param topK           maximum number of facts to return
     * @return most relevant facts, ordered by relevance (best first)
     */
    List<AgentFact> recall(String conversationId, String query, int topK);

    /**
     * Removes a specific fact from memory.
     *
     * @param conversationId conversation or user scope
     * @param factId         identifier of the fact to remove
     */
    void forget(String conversationId, String factId);
}
