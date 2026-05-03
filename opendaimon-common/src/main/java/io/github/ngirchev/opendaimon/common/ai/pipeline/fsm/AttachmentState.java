package io.github.ngirchev.opendaimon.common.ai.pipeline.fsm;

/**
 * States for document attachment processing FSM.
 *
 * <p>Terminal states: {@link #IMAGE_PASSTHROUGH}, {@link #RAG_INDEXED},
 * {@link #IMAGE_FALLBACK}, {@link #ERROR}.
 */
public enum AttachmentState {

    /** Initial state — attachment received, not yet classified. */
    RECEIVED,

    /** Attachment type determined (image / document / unsupported). */
    CLASSIFIED,

    /** Document content analyzed (text-extractable vs image-only PDF). */
    ANALYZED,

    /** Text successfully extracted from document (PDFBox / Tika). */
    TEXT_EXTRACTED,

    /** Vision OCR completed (success or failure stored in context). */
    VISION_OCR_COMPLETE,

    // --- Terminal states ---

    /** Image attachment — bypasses document processing, passed directly to gateway. */
    IMAGE_PASSTHROUGH,

    /** Document chunks indexed in VectorStore — ready for RAG augmentation. */
    RAG_INDEXED,

    /** Vision OCR failed — rendered PDF page images used as fallback for direct vision. */
    IMAGE_FALLBACK,

    /** Unrecognized attachment type or processing error. */
    ERROR
}
