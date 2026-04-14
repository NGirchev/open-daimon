package io.github.ngirchev.opendaimon.common.ai.pipeline.fsm;

import io.github.ngirchev.fsm.StateContext;
import io.github.ngirchev.fsm.Transition;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.command.IChatCommand;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import lombok.Getter;
import lombok.Setter;
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
    @Getter
    private final ICommand<?> command;
    @Getter
    private final Map<String, String> metadata;

    // --- Validation results (set by validate action) ---
    @Setter
    @Getter
    private IChatCommand<?> chatCommand;
    @Setter
    @Getter
    private List<Attachment> attachments = List.of();
    @Setter
    @Getter
    private String userText;

    // --- Classification results (set by classify action) ---
    @Setter
    @Getter
    private boolean hasDocuments;
    @Setter
    @Getter
    private boolean hasFollowUpRag;
    @Setter
    @Getter
    private List<String> unrecognizedTypes = List.of();

    // --- Document processing results (set by processDocuments/collectResults actions) ---
    @Setter
    @Getter
    private List<AttachmentProcessingContext> fsmContexts = List.of();
    @Setter
    @Getter
    private List<String> allChunkTexts = new ArrayList<>();
    @Setter
    @Getter
    private List<String> processedDocumentIds = new ArrayList<>();
    @Setter
    @Getter
    private List<String> processedFilenames = new ArrayList<>();
    @Setter
    @Getter
    private List<Attachment> mutableAttachments = new ArrayList<>();
    @Setter
    @Getter
    private List<String> pdfAsImageFilenames = new ArrayList<>();

    // --- Query augmentation (set by augmentQuery/processFollowUpRag actions) ---
    @Setter
    @Getter
    private String augmentedQuery;

    // --- Output ---
    @Setter
    @Getter
    private AICommand result;
    @Setter
    @Getter
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

    public boolean isChatCommand() {
        return chatCommand != null;
    }

    public boolean isNotChatCommand() {
        return chatCommand == null;
    }

    public boolean hasUnrecognized() {
        return !unrecognizedTypes.isEmpty();
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
