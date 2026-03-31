package io.github.ngirchev.opendaimon.common.ai.pipeline.fsm;

import io.github.ngirchev.fsm.StateContext;
import io.github.ngirchev.fsm.Transition;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.command.IChatCommand;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Domain object that flows through the AI request pipeline FSM.
 *
 * <p>Implements {@link StateContext} so that {@code ExDomainFsm} can read/write
 * the current state directly on this object.
 *
 * <p>Mutable by design — FSM actions populate intermediate results as the context
 * moves through states.
 */
public final class AIRequestContext implements StateContext<AIRequestState> {

    // --- StateContext fields ---
    private AIRequestState state;
    private Transition<AIRequestState> currentTransition;

    // --- Input (immutable after construction) ---
    private final ICommand<?> command;
    private final Map<String, String> metadata;

    // --- Validation results (set by validate action) ---
    private IChatCommand<?> chatCommand;
    private List<Attachment> attachments = List.of();
    private String userText;

    // --- Classification results (set by classify action) ---
    private boolean hasDocuments;
    private boolean hasFollowUpRag;
    private List<String> unrecognizedTypes = List.of();

    // --- Document processing results (set by processDocuments/collectResults actions) ---
    private List<AttachmentProcessingContext> fsmContexts = List.of();
    private List<String> allChunkTexts = new ArrayList<>();
    private List<String> processedDocumentIds = new ArrayList<>();
    private List<String> processedFilenames = new ArrayList<>();
    private List<Attachment> mutableAttachments = new ArrayList<>();
    private List<String> pdfAsImageFilenames = new ArrayList<>();

    // --- Query augmentation (set by augmentQuery/processFollowUpRag actions) ---
    private String augmentedQuery;

    // --- Output ---
    private AICommand result;
    private String errorMessage;

    public AIRequestContext(ICommand<?> command, Map<String, String> metadata) {
        this.command = command;
        this.metadata = metadata;
        this.state = AIRequestState.RECEIVED;
    }

    // --- StateContext implementation ---

    @Override
    public AIRequestState getState() {
        return state;
    }

    @Override
    public void setState(AIRequestState state) {
        this.state = state;
    }

    @Nullable
    @Override
    public Transition<AIRequestState> getCurrentTransition() {
        return currentTransition;
    }

    @Override
    public void setCurrentTransition(@Nullable Transition<AIRequestState> transition) {
        this.currentTransition = transition;
    }

    // --- Input accessors ---

    public ICommand<?> getCommand() {
        return command;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    // --- Validation ---

    public IChatCommand<?> getChatCommand() {
        return chatCommand;
    }

    public void setChatCommand(IChatCommand<?> chatCommand) {
        this.chatCommand = chatCommand;
    }

    public List<Attachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<Attachment> attachments) {
        this.attachments = attachments;
    }

    public String getUserText() {
        return userText;
    }

    public void setUserText(String userText) {
        this.userText = userText;
    }

    // --- Classification guards ---

    public boolean isChatCommand() {
        return chatCommand != null;
    }

    public boolean isNotChatCommand() {
        return chatCommand == null;
    }

    public boolean isHasDocuments() {
        return hasDocuments;
    }

    public void setHasDocuments(boolean hasDocuments) {
        this.hasDocuments = hasDocuments;
    }

    public boolean isHasFollowUpRag() {
        return hasFollowUpRag;
    }

    public void setHasFollowUpRag(boolean hasFollowUpRag) {
        this.hasFollowUpRag = hasFollowUpRag;
    }

    public List<String> getUnrecognizedTypes() {
        return unrecognizedTypes;
    }

    public void setUnrecognizedTypes(List<String> unrecognizedTypes) {
        this.unrecognizedTypes = unrecognizedTypes;
    }

    public boolean hasUnrecognized() {
        return unrecognizedTypes != null && !unrecognizedTypes.isEmpty();
    }

    /**
     * No documents and no follow-up RAG — command passes directly to factory.
     */
    public boolean isPassthrough() {
        return !hasDocuments && !hasFollowUpRag;
    }

    /**
     * Follow-up RAG: no new documents, but stored document IDs exist.
     */
    public boolean isFollowUpRag() {
        return !hasDocuments && hasFollowUpRag;
    }

    // --- Document processing ---

    public List<AttachmentProcessingContext> getFsmContexts() {
        return fsmContexts;
    }

    public void setFsmContexts(List<AttachmentProcessingContext> fsmContexts) {
        this.fsmContexts = fsmContexts;
    }

    public List<String> getAllChunkTexts() {
        return allChunkTexts;
    }

    public void setAllChunkTexts(List<String> allChunkTexts) {
        this.allChunkTexts = allChunkTexts;
    }

    public List<String> getProcessedDocumentIds() {
        return processedDocumentIds;
    }

    public void setProcessedDocumentIds(List<String> processedDocumentIds) {
        this.processedDocumentIds = processedDocumentIds;
    }

    public List<String> getProcessedFilenames() {
        return processedFilenames;
    }

    public void setProcessedFilenames(List<String> processedFilenames) {
        this.processedFilenames = processedFilenames;
    }

    public List<Attachment> getMutableAttachments() {
        return mutableAttachments;
    }

    public void setMutableAttachments(List<Attachment> mutableAttachments) {
        this.mutableAttachments = mutableAttachments;
    }

    public List<String> getPdfAsImageFilenames() {
        return pdfAsImageFilenames;
    }

    public void setPdfAsImageFilenames(List<String> pdfAsImageFilenames) {
        this.pdfAsImageFilenames = pdfAsImageFilenames;
    }

    // --- Query augmentation ---

    public String getAugmentedQuery() {
        return augmentedQuery;
    }

    public void setAugmentedQuery(String augmentedQuery) {
        this.augmentedQuery = augmentedQuery;
    }

    // --- Output ---

    public AICommand getResult() {
        return result;
    }

    public void setResult(AICommand result) {
        this.result = result;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    // --- Terminal state queries ---

    public boolean isPassthroughCompleted() {
        return state == AIRequestState.PASSTHROUGH;
    }

    public boolean isCommandBuilt() {
        return state == AIRequestState.COMMAND_BUILT;
    }

    public boolean isError() {
        return state == AIRequestState.ERROR;
    }

    @Override
    public String toString() {
        return "AIRequestContext{state=" + state + ", hasDocuments=" + hasDocuments
                + ", hasFollowUpRag=" + hasFollowUpRag + '}';
    }
}
