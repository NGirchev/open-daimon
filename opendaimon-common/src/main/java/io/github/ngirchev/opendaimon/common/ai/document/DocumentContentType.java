package io.github.ngirchev.opendaimon.common.ai.document;

/**
 * Describes the content type of a document attachment for capability routing.
 */
public enum DocumentContentType {
    /**
     * Text can be extracted directly (standard PDF with text layer, DOCX, TXT, etc.).
     * Only CHAT capability is sufficient.
     */
    TEXT_EXTRACTABLE,

    /**
     * No text layer — requires VISION model for OCR (scanned PDFs, image-only PDFs).
     * Needs CHAT + VISION capabilities.
     */
    IMAGE_ONLY,

    /**
     * Document format not supported for processing.
     */
    UNSUPPORTED
}
