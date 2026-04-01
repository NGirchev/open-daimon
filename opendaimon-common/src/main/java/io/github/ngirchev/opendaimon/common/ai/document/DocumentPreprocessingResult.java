package io.github.ngirchev.opendaimon.common.ai.document;

import io.github.ngirchev.opendaimon.common.model.Attachment;

import java.util.List;

/**
 * Result of document preprocessing — contains RAG document IDs,
 * relevant text chunks, and any generated image attachments.
 *
 * <p>Produced by {@link IDocumentPreprocessor} and consumed by the gateway
 * or factory to build the final AI command.
 *
 * @param documentId unique ID of the processed document in VectorStore (null if processing failed)
 * @param relevantChunkTexts extracted relevant text chunks for RAG augmentation
 * @param imageAttachments image attachments from PDF rendering (if vision OCR failed, used as fallback)
 * @param visionExtractionSucceeded true if vision OCR succeeded and images are no longer needed
 */
public record DocumentPreprocessingResult(
        String documentId,
        List<String> relevantChunkTexts,
        List<Attachment> imageAttachments,
        boolean visionExtractionSucceeded
) {

    public static DocumentPreprocessingResult empty() {
        return new DocumentPreprocessingResult(null, List.of(), List.of(), false);
    }

    public boolean hasDocumentId() {
        return documentId != null && !documentId.isBlank();
    }
}
