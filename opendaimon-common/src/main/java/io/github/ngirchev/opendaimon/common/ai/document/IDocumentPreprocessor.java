package io.github.ngirchev.opendaimon.common.ai.document;

import io.github.ngirchev.opendaimon.common.model.Attachment;

/**
 * Preprocesses document attachments for the AI pipeline before the gateway call.
 *
 * <p>Handles the full document processing flow:
 * <ul>
 *   <li>Text-extractable documents: extract text, chunk, index in RAG</li>
 *   <li>Image-only PDFs: render to images, OCR via VISION model, index in RAG</li>
 * </ul>
 *
 * <p>Implementations live in provider modules (e.g. {@code opendaimon-spring-ai}).
 */
public interface IDocumentPreprocessor {

    /**
     * Preprocesses a document attachment: extracts text, indexes in RAG,
     * and returns results for the AI command.
     *
     * @param attachment document attachment to preprocess
     * @param userQuery user's question (for RAG relevance scoring)
     * @param analysisResult result from {@link IDocumentContentAnalyzer}
     * @return preprocessing result with document ID, chunks, and optional image attachments
     */
    DocumentPreprocessingResult preprocess(Attachment attachment, String userQuery, DocumentAnalysisResult analysisResult);
}
