package io.github.ngirchev.opendaimon.common.ai.document;

import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;

import java.util.Set;

/**
 * Result of document content analysis — determines what model capabilities
 * are required to process the document.
 *
 * <p>Used by {@link IDocumentContentAnalyzer} to inform capability routing
 * in {@code DefaultAICommandFactory} before the gateway call.
 *
 * @param contentType the detected content type (text-extractable, image-only, or unsupported)
 * @param requiredCapabilities model capabilities needed to process this document
 */
public record DocumentAnalysisResult(
        DocumentContentType contentType,
        Set<ModelCapabilities> requiredCapabilities
) {

    /**
     * Document with extractable text — CHAT is sufficient.
     */
    public static DocumentAnalysisResult textExtractable() {
        return new DocumentAnalysisResult(DocumentContentType.TEXT_EXTRACTABLE, Set.of(ModelCapabilities.CHAT));
    }

    /**
     * Image-only document (scanned PDF) — requires VISION for OCR.
     */
    public static DocumentAnalysisResult requiresVision() {
        return new DocumentAnalysisResult(DocumentContentType.IMAGE_ONLY, Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION));
    }

    /**
     * Unsupported document format.
     */
    public static DocumentAnalysisResult unsupported() {
        return new DocumentAnalysisResult(DocumentContentType.UNSUPPORTED, Set.of());
    }

    /**
     * @return true if this document requires VISION capability for processing
     */
    public boolean needsVision() {
        return requiredCapabilities.contains(ModelCapabilities.VISION);
    }
}
