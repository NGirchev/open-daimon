package io.github.ngirchev.opendaimon.common.ai.document;

import io.github.ngirchev.opendaimon.common.model.Attachment;

/**
 * Analyzes document content to determine required model capabilities
 * before the AI gateway call.
 *
 * <p>This interface enables capability detection at the factory level
 * (in {@code DefaultAICommandFactory}), so that priority-based routing
 * can correctly block or allow VISION usage for image-only PDFs.
 *
 * <p>Implementations live in provider modules (e.g. {@code opendaimon-spring-ai}).
 */
public interface IDocumentContentAnalyzer {

    /**
     * Analyzes document content to determine required capabilities.
     *
     * <p>For PDF: checks if text is extractable via PDFBox or if VISION is needed for OCR.
     * <p>For other documents (DOCX, TXT, etc.): always returns {@link DocumentContentType#TEXT_EXTRACTABLE}.
     *
     * @param attachment document attachment to analyze (must satisfy {@link Attachment#isDocument()})
     * @return analysis result with content type and required capabilities
     */
    DocumentAnalysisResult analyze(Attachment attachment);
}
