package io.github.ngirchev.opendaimon.common.ai.pipeline;

import java.util.List;

/**
 * Augments user queries with RAG (Retrieval-Augmented Generation) context.
 *
 * <p>Used by {@link AIRequestPipeline} for two scenarios:
 * <ul>
 *   <li>New documents: augment query with freshly extracted text chunks</li>
 *   <li>Follow-up messages: augment query with stored document chunks from VectorStore</li>
 * </ul>
 */
public interface IRagQueryAugmenter {

    /**
     * Augments the user query with provided text chunks.
     *
     * @param userQuery original user query
     * @param chunkTexts extracted text chunks from document processing
     * @param documentFilenames filenames of processed documents (for context placeholder)
     * @return augmented query with RAG context
     */
    String augment(String userQuery, List<String> chunkTexts, List<String> documentFilenames);

    /**
     * Augments the user query with chunks from previously processed documents.
     * Used for follow-up messages where documents were processed in a prior turn.
     *
     * @param userQuery original user query
     * @param documentIds stored document IDs from previous processing
     * @return augmented query, or original query if no chunks found
     */
    String augmentFromStoredDocuments(String userQuery, List<String> documentIds);
}
