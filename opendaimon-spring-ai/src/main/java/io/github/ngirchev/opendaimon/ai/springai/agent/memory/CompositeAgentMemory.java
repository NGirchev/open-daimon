package io.github.ngirchev.opendaimon.ai.springai.agent.memory;

import io.github.ngirchev.opendaimon.common.agent.memory.AgentFact;
import io.github.ngirchev.opendaimon.common.agent.memory.AgentMemory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines multiple {@link AgentMemory} implementations into a single view.
 *
 * <p>Store operations are dispatched to all delegates.
 * Recall operations merge results from all delegates, deduplicating by fact ID
 * and preserving insertion order (first occurrence wins).
 * Forget operations are dispatched to all delegates.
 */
public class CompositeAgentMemory implements AgentMemory {

    private final List<AgentMemory> delegates;

    public CompositeAgentMemory(List<AgentMemory> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public void store(String conversationId, AgentFact fact) {
        for (AgentMemory delegate : delegates) {
            delegate.store(conversationId, fact);
        }
    }

    @Override
    public List<AgentFact> recall(String conversationId, String query, int topK) {
        Map<String, AgentFact> deduplicated = new LinkedHashMap<>();

        for (AgentMemory delegate : delegates) {
            List<AgentFact> results = delegate.recall(conversationId, query, topK);
            for (AgentFact fact : results) {
                deduplicated.putIfAbsent(fact.id(), fact);
            }
        }

        return new ArrayList<>(deduplicated.values())
                .subList(0, Math.min(deduplicated.size(), topK));
    }

    @Override
    public void forget(String conversationId, String factId) {
        for (AgentMemory delegate : delegates) {
            delegate.forget(conversationId, factId);
        }
    }
}
