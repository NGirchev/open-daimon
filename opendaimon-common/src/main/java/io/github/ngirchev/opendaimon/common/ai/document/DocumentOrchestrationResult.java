package io.github.ngirchev.opendaimon.common.ai.document;

import io.github.ngirchev.opendaimon.common.model.Attachment;

import java.util.List;

/**
 * Result of document orchestration — the user query (possibly augmented with RAG context),
 * modified attachment list, and metadata for persistence.
 *
 * <p>Produced by {@link IDocumentOrchestrator} and consumed by the gateway
 * to build the final AI request.
 *
 * @param augmentedUserQuery user query with RAG context prepended (or original if no RAG)
 * @param attachments modified attachment list (may include rendered PDF page images as fallback)
 * @param pdfAsImageFilenames PDF filenames that were converted to images (for system message context)
 * @param processedDocumentIds RAG document IDs for persistence in message metadata
 * @param processedFilenames original filenames corresponding to processedDocumentIds
 */
public record DocumentOrchestrationResult(
        String augmentedUserQuery,
        List<Attachment> attachments,
        List<String> pdfAsImageFilenames,
        List<String> processedDocumentIds,
        List<String> processedFilenames
) {

    /**
     * No documents processed — returns original query and attachments unchanged.
     */
    public static DocumentOrchestrationResult unchanged(String userQuery, List<Attachment> attachments) {
        return new DocumentOrchestrationResult(userQuery, attachments, List.of(), List.of(), List.of());
    }

    public boolean hasProcessedDocuments() {
        return !processedDocumentIds.isEmpty();
    }
}
