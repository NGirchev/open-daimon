package io.github.ngirchev.opendaimon.common.ai.pipeline.fsm;

/**
 * Actions invoked by the AI request pipeline FSM during state transitions.
 *
 * <p>Each method corresponds to a processing step. Implementations populate
 * the {@link AIRequestContext} with intermediate and final results.
 *
 * <p>Implementations must not throw unchecked exceptions for expected failures.
 * Instead, they should set appropriate flags on the context so that
 * the FSM conditions can route to the correct path.
 */
public interface AIRequestPipelineActions {

    /**
     * Validate the command: check if it's a chat command, parse attachments and user text.
     * Called during RECEIVED → VALIDATED transition.
     *
     * <p>Sets {@link AIRequestContext#getChatCommand()},
     * {@link AIRequestContext#getAttachments()},
     * {@link AIRequestContext#getUserText()}.
     */
    void validate(AIRequestContext ctx);

    /**
     * Classify the request: detect documents, follow-up RAG, unrecognized types.
     * Called during VALIDATED → CLASSIFIED transition.
     *
     * <p>Sets {@link AIRequestContext#isHasDocuments()},
     * {@link AIRequestContext#isHasFollowUpRag()},
     * {@link AIRequestContext#getUnrecognizedTypes()}.
     */
    void classify(AIRequestContext ctx);

    /**
     * Build a passthrough command — no document processing needed.
     * Called during transitions to PASSTHROUGH state.
     *
     * <p>Sets {@link AIRequestContext#getResult()}.
     */
    void buildPassthrough(AIRequestContext ctx);

    /**
     * Process follow-up RAG: augment query from stored document IDs.
     * Called during CLASSIFIED → FOLLOW_UP_RAG transition.
     *
     * <p>Sets {@link AIRequestContext#getAugmentedQuery()}.
     */
    void processFollowUpRag(AIRequestContext ctx);

    /**
     * Process documents: run each document attachment through the document FSM.
     * Called during CLASSIFIED → DOCUMENTS_PROCESSING transition.
     *
     * <p>Sets {@link AIRequestContext#getFsmContexts()}.
     */
    void processDocuments(AIRequestContext ctx);

    /**
     * Collect results from document FSM contexts: chunks, IDs, filenames, image fallbacks.
     * Called during DOCUMENTS_PROCESSING → RESULTS_COLLECTED transition.
     *
     * <p>Sets chunk texts, document IDs, filenames, mutable attachments, metadata.
     */
    void collectResults(AIRequestContext ctx);

    /**
     * Augment user query with RAG context from extracted chunks.
     * Called during RESULTS_COLLECTED → QUERY_AUGMENTED transition.
     *
     * <p>Sets {@link AIRequestContext#getAugmentedQuery()}.
     */
    void augmentQuery(AIRequestContext ctx);

    /**
     * Build the final orchestrated command with augmented query and modified attachments.
     * Called during transitions to COMMAND_BUILT state.
     *
     * <p>Sets {@link AIRequestContext#getResult()}.
     */
    void buildCommand(AIRequestContext ctx);

    /**
     * Handle error: unsupported attachment types detected.
     * Called during CLASSIFIED → ERROR transition.
     *
     * <p>Sets {@link AIRequestContext#getErrorMessage()}.
     */
    void handleError(AIRequestContext ctx);
}
