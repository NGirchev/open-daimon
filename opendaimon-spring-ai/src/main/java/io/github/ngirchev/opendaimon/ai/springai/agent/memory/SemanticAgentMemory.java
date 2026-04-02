package io.github.ngirchev.opendaimon.ai.springai.agent.memory;

import io.github.ngirchev.opendaimon.common.agent.memory.AgentFact;
import io.github.ngirchev.opendaimon.common.agent.memory.AgentMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * VectorStore-backed semantic memory for agents.
 *
 * <p>Stores facts as embedded documents and retrieves them using similarity search.
 * Each fact is tagged with a conversation ID in metadata so recall can be scoped
 * to a specific conversation or user.
 *
 * <p>Reuses the existing Spring AI VectorStore infrastructure
 * (SimpleVectorStore or PGVector depending on configuration).
 */
@Slf4j
public class SemanticAgentMemory implements AgentMemory {

    private static final String METADATA_CONVERSATION_ID = "agent_conversation_id";
    private static final String METADATA_FACT_ID = "agent_fact_id";
    private static final String METADATA_FACT_TYPE = "agent_fact";
    private static final String METADATA_CREATED_AT = "agent_created_at";

    private final VectorStore vectorStore;
    private final double similarityThreshold;

    public SemanticAgentMemory(VectorStore vectorStore, double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.similarityThreshold = similarityThreshold;
    }

    @Override
    public void store(String conversationId, AgentFact fact) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(METADATA_CONVERSATION_ID, conversationId);
        metadata.put(METADATA_FACT_ID, fact.id());
        metadata.put(METADATA_FACT_TYPE, "true");
        metadata.put(METADATA_CREATED_AT, fact.createdAt().toString());

        if (fact.metadata() != null) {
            fact.metadata().forEach(metadata::put);
        }

        Document document = new Document(fact.id(), fact.content(), metadata);
        vectorStore.add(List.of(document));

        log.info("AgentMemory stored fact: id={}, conversationId={}, contentLength={}",
                fact.id(), conversationId, fact.content().length());
    }

    @Override
    public List<AgentFact> recall(String conversationId, String query, int topK) {
        String sanitizedId = sanitizeFilterValue(conversationId);
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression(METADATA_FACT_TYPE + " == 'true' && "
                        + METADATA_CONVERSATION_ID + " == '" + sanitizedId + "'")
                .build();

        List<Document> results = vectorStore.similaritySearch(searchRequest);

        List<AgentFact> facts = results.stream()
                .map(this::toAgentFact)
                .toList();

        log.info("AgentMemory recall: conversationId={}, query='{}', topK={}, found={}",
                conversationId, truncate(query, 50), topK, facts.size());
        return facts;
    }

    @Override
    public void forget(String conversationId, String factId) {
        vectorStore.delete(List.of(factId));
        log.info("AgentMemory forgot fact: id={}, conversationId={}", factId, conversationId);
    }

    private AgentFact toAgentFact(Document document) {
        Map<String, Object> meta = document.getMetadata();
        String id = meta.containsKey(METADATA_FACT_ID)
                ? meta.get(METADATA_FACT_ID).toString()
                : document.getId();

        Instant createdAt;
        try {
            createdAt = meta.containsKey(METADATA_CREATED_AT)
                    ? Instant.parse(meta.get(METADATA_CREATED_AT).toString())
                    : Instant.now();
        } catch (Exception e) {
            createdAt = Instant.now();
        }

        Map<String, String> factMetadata = new HashMap<>();
        meta.forEach((k, v) -> {
            if (!k.startsWith("agent_") && v != null) {
                factMetadata.put(k, v.toString());
            }
        });

        return new AgentFact(id, document.getText(), factMetadata, createdAt);
    }

    /**
     * Creates a new fact with a generated UUID.
     */
    public static AgentFact createFact(String content, Map<String, String> metadata) {
        return new AgentFact(UUID.randomUUID().toString(), content, metadata, Instant.now());
    }

    /**
     * Strips characters that could break filter expression syntax to prevent injection.
     * Only allows alphanumeric, colon, hyphen, underscore, and dot.
     */
    private static String sanitizeFilterValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^a-zA-Z0-9:_.\\-]", "");
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
