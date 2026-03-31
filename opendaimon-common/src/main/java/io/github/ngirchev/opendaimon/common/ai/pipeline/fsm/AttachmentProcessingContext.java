package io.github.ngirchev.opendaimon.common.ai.pipeline.fsm;

import io.github.ngirchev.fsm.StateContext;
import io.github.ngirchev.fsm.Transition;
import io.github.ngirchev.opendaimon.common.ai.document.DocumentContentType;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Domain object that flows through the document processing FSM.
 *
 * <p>Implements {@link StateContext} so that {@code ExDomainFsm} can read/write
 * the current state directly on this object. Each attachment gets its own context
 * instance; the pipeline collects results from all contexts after processing.
 *
 * <p>Mutable by design — FSM actions populate intermediate results as the context
 * moves through states.
 */
public final class AttachmentProcessingContext implements StateContext<AttachmentState> {

    // --- StateContext fields ---
    private AttachmentState state;
    private Transition<AttachmentState> currentTransition;

    // --- Input (immutable after construction) ---
    private final Attachment attachment;
    private final String userText;

    // --- Intermediate results (set by FSM actions) ---
    private DocumentContentType documentContentType;
    private List<String> extractedChunks = new ArrayList<>();
    private boolean visionOcrSucceeded;
    private List<Attachment> imageAttachments = new ArrayList<>();

    // --- Output ---
    private String documentId;
    private String processedFilename;
    private String errorMessage;

    public AttachmentProcessingContext(Attachment attachment, String userText) {
        this.attachment = attachment;
        this.userText = userText;
        this.state = AttachmentState.RECEIVED;
    }

    // --- StateContext implementation ---

    @Override
    public AttachmentState getState() {
        return state;
    }

    @Override
    public void setState(AttachmentState state) {
        this.state = state;
    }

    @Nullable
    @Override
    public Transition<AttachmentState> getCurrentTransition() {
        return currentTransition;
    }

    @Override
    public void setCurrentTransition(@Nullable Transition<AttachmentState> transition) {
        this.currentTransition = transition;
    }

    // --- Input accessors ---

    public Attachment getAttachment() {
        return attachment;
    }

    public String getUserText() {
        return userText;
    }

    // --- Classification helpers (used as FSM guards) ---

    public boolean isImage() {
        return attachment.isImage();
    }

    public boolean isDocument() {
        return attachment.isDocument();
    }

    // --- Content analysis ---

    public DocumentContentType getDocumentContentType() {
        return documentContentType;
    }

    public void setDocumentContentType(DocumentContentType documentContentType) {
        this.documentContentType = documentContentType;
    }

    public boolean isTextExtractable() {
        return documentContentType == DocumentContentType.TEXT_EXTRACTABLE;
    }

    public boolean isImageOnly() {
        return documentContentType == DocumentContentType.IMAGE_ONLY;
    }

    // --- Text extraction ---

    public List<String> getExtractedChunks() {
        return extractedChunks;
    }

    public void setExtractedChunks(List<String> extractedChunks) {
        this.extractedChunks = extractedChunks;
    }

    public boolean hasExtractedChunks() {
        return extractedChunks != null && !extractedChunks.isEmpty();
    }

    // --- Vision OCR ---

    public boolean isVisionOcrSucceeded() {
        return visionOcrSucceeded;
    }

    public void setVisionOcrSucceeded(boolean visionOcrSucceeded) {
        this.visionOcrSucceeded = visionOcrSucceeded;
    }

    public List<Attachment> getImageAttachments() {
        return imageAttachments;
    }

    public void setImageAttachments(List<Attachment> imageAttachments) {
        this.imageAttachments = imageAttachments;
    }

    // --- Output ---

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getProcessedFilename() {
        return processedFilename;
    }

    public void setProcessedFilename(String processedFilename) {
        this.processedFilename = processedFilename;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    // --- Terminal state queries ---

    public boolean isTerminalSuccess() {
        return state == AttachmentState.RAG_INDEXED
                || state == AttachmentState.IMAGE_PASSTHROUGH
                || state == AttachmentState.IMAGE_FALLBACK;
    }

    public boolean isRagIndexed() {
        return state == AttachmentState.RAG_INDEXED;
    }

    public boolean isImageFallback() {
        return state == AttachmentState.IMAGE_FALLBACK;
    }

    public boolean isError() {
        return state == AttachmentState.ERROR;
    }

    @Override
    public String toString() {
        return "AttachmentProcessingContext{" +
                "state=" + state +
                ", attachment=" + attachment +
                ", documentContentType=" + documentContentType +
                '}';
    }
}
