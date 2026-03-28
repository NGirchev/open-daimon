package io.github.ngirchev.opendaimon.ai.springai.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import io.github.ngirchev.opendaimon.ai.springai.config.RAGProperties;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for RAG (Retrieval-Augmented Generation).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Find relevant chunks in VectorStore by query</li>
 *   <li>Build augmented prompt with document context</li>
 * </ul>
 *
 * <p>Used to enrich user queries with context from uploaded PDF/document chunks.
 */
@Slf4j
@RequiredArgsConstructor
public class FileRAGService {

    /**
     * Upper bound for "fetch all by documentId" retrieval.
     *
     * <p>VectorStore API is similarity-search based and requires topK; using the regular rag.top-k (often 5)
     * can truncate context and break follow-up questions that reference later chunks.
     */
    private static final int FIND_ALL_TOP_K = 10_000;

    private final VectorStore vectorStore;
    private final RAGProperties ragProperties;

    /**
     * Finds relevant chunks for the query from a specific document.
     *
     * @param query user query text
     * @param documentId document id (from processPdf)
     * @return list of relevant documents sorted by similarity
     */
    public List<Document> findRelevantContext(String query, String documentId) {
        log.debug("Searching for relevant context: query='{}', documentId={}", 
                truncateForLog(query), documentId);
        
        FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
        
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(ragProperties.getTopK())
                .similarityThreshold(ragProperties.getSimilarityThreshold())
                .filterExpression(filterBuilder.eq("documentId", documentId).build())
                .build();
        
        List<Document> results = vectorStore.similaritySearch(searchRequest);
        
        log.info("Found {} relevant chunks for documentId={}", results.size(), documentId);
        
        return results;
    }

    /**
     * Returns all chunks belonging to a specific document, bypassing similarity threshold.
     *
     * <p>Used for freshly vision-extracted text where all chunks are inherently relevant
     * (the user just uploaded this document). Avoids false negatives from cross-language
     * similarity mismatch (e.g. Russian query vs English extracted text).
     *
     * @param documentId document id (from processExtractedText or processPdf)
     * @return all chunks for the document
     */
    public List<Document> findAllByDocumentId(String documentId) {
        log.debug("Fetching all chunks for documentId={} (no similarity threshold)", documentId);

        FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
        int topK = Math.max(ragProperties.getTopK(), FIND_ALL_TOP_K);

        SearchRequest searchRequest = SearchRequest.builder()
                .query("document content")
                .topK(topK)
                .similarityThreshold(0.0)
                .filterExpression(filterBuilder.eq("documentId", documentId).build())
                .build();

        List<Document> results = vectorStore.similaritySearch(searchRequest);

        log.info("Found {} total chunks for documentId={} (topK={})", results.size(), documentId, topK);

        return results;
    }

    /**
     * Finds relevant chunks for the query across all documents.
     *
     * @param query user query text
     * @return list of relevant documents sorted by similarity
     */
    public List<Document> findRelevantContext(String query) {
        log.debug("Searching for relevant context across all documents: query='{}'", 
                truncateForLog(query));
        
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(ragProperties.getTopK())
                .similarityThreshold(ragProperties.getSimilarityThreshold())
                .build();
        
        log.debug("RAG: Performing semantic search in VectorStore (topK={}, threshold={})", 
                ragProperties.getTopK(), ragProperties.getSimilarityThreshold());
        
        List<Document> results = vectorStore.similaritySearch(searchRequest);
        
        log.debug("RAG: Semantic search found {} relevant chunks", results.size());
        
        return results;
    }

    /**
     * Creates augmented prompt with document context.
     *
     * <p>If context is empty, returns the original query unchanged.
     *
     * @param userQuery user query
     * @param context list of relevant documents
     * @return augmented prompt with context
     */
    public String createAugmentedPrompt(String userQuery, List<Document> context) {
        if (context == null || context.isEmpty()) {
            log.debug("No context provided, returning original query");
            return userQuery;
        }
        
        String contextText = context.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        String template = ragProperties.getPrompts().getAugmentedPromptTemplate();
        String augmentedPrompt = String.format(template, contextText, userQuery);

        log.debug("Created augmented prompt with {} characters of context", contextText.length());
        
        return augmentedPrompt;
    }

    private String truncateForLog(String text) {
        if (text == null) return "null";
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }
}
