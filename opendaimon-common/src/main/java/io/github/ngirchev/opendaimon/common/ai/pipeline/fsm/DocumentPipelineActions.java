package io.github.ngirchev.opendaimon.common.ai.pipeline.fsm;

/**
 * Actions invoked by the document processing FSM during state transitions.
 *
 * <p>Each method corresponds to a processing step. Implementations populate
 * the {@link AttachmentProcessingContext} with intermediate and final results.
 * The FSM guarantees that actions are called in the correct order based on
 * the state machine transitions.
 *
 * <p>Implementations must not throw unchecked exceptions for expected failures
 * (e.g., text extraction returning empty). Instead, they should set appropriate
 * flags on the context (e.g., {@code setExtractedChunks(emptyList())}) so that
 * the FSM conditions can route to the correct fallback path.
 */
public interface DocumentPipelineActions {

    /**
     * Classify the attachment type and set initial metadata on the context.
     * Called during RECEIVED → CLASSIFIED transition.
     *
     * <p>Sets {@link AttachmentProcessingContext#getProcessedFilename()}.
     */
    void classify(AttachmentProcessingContext ctx);

    /**
     * Analyze document content to determine if text is extractable or image-only.
     * Called during CLASSIFIED → ANALYZED transition (documents only).
     *
     * <p>Sets {@link AttachmentProcessingContext#getDocumentContentType()}.
     */
    void analyzeContent(AttachmentProcessingContext ctx);

    /**
     * Extract text from document using PDFBox (PDF) or Tika (other formats).
     * Called during ANALYZED → TEXT_EXTRACTED transition.
     *
     * <p>Sets {@link AttachmentProcessingContext#getExtractedChunks()}.
     * If extraction returns empty, the FSM falls back to vision OCR.
     */
    void extractText(AttachmentProcessingContext ctx);

    /**
     * Run vision OCR on image-only PDF (render pages, send to vision model).
     * Called during ANALYZED → VISION_OCR_COMPLETE or TEXT_EXTRACTED → VISION_OCR_COMPLETE.
     *
     * <p>Sets {@link AttachmentProcessingContext#isVisionOcrSucceeded()},
     * {@link AttachmentProcessingContext#getExtractedChunks()} (if OCR succeeded),
     * and {@link AttachmentProcessingContext#getImageAttachments()} (rendered page images).
     */
    void runVisionOcr(AttachmentProcessingContext ctx);

    /**
     * Index extracted text chunks in VectorStore for RAG retrieval.
     * Called during TEXT_EXTRACTED → RAG_INDEXED or VISION_OCR_COMPLETE → RAG_INDEXED.
     *
     * <p>Sets {@link AttachmentProcessingContext#getDocumentId()}.
     */
    void indexInRag(AttachmentProcessingContext ctx);

    /**
     * Handle unsupported attachment type.
     * Called during CLASSIFIED → ERROR transition.
     *
     * <p>Sets {@link AttachmentProcessingContext#getErrorMessage()}.
     */
    void handleUnsupported(AttachmentProcessingContext ctx);
}
