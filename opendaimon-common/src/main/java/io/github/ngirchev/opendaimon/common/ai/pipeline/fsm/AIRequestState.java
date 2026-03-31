package io.github.ngirchev.opendaimon.common.ai.pipeline.fsm;

/**
 * States for the AI request processing pipeline FSM.
 *
 * <p>Terminal states: {@link #PASSTHROUGH}, {@link #COMMAND_BUILT}, {@link #ERROR}.
 *
 * <p>Transition graph:
 * <pre>
 * RECEIVED ──[PREPARE]──▶ VALIDATED
 *
 * VALIDATED ──[auto]──┬─[notChatCommand]──▶ PASSTHROUGH (terminal)
 *                     └─[isChatCommand]───▶ CLASSIFIED
 *
 * CLASSIFIED ──[auto]──┬─[hasUnrecognized]────────▶ ERROR (terminal)
 *                      ├─[isPassthrough]──────────▶ PASSTHROUGH (terminal)
 *                      ├─[followUpRag]────────────▶ FOLLOW_UP_RAG
 *                      └─[hasDocuments]───────────▶ DOCUMENTS_PROCESSING
 *
 * FOLLOW_UP_RAG ──[auto]──▶ COMMAND_BUILT (terminal)
 *
 * DOCUMENTS_PROCESSING ──[auto]──▶ RESULTS_COLLECTED
 *
 * RESULTS_COLLECTED ──[auto]──▶ QUERY_AUGMENTED
 *
 * QUERY_AUGMENTED ──[auto]──▶ COMMAND_BUILT (terminal)
 * </pre>
 */
public enum AIRequestState {

    /** Initial state — command received, not yet validated. */
    RECEIVED,

    /** Command type checked, attachments parsed. */
    VALIDATED,

    /** Routing decision made: passthrough / follow-up RAG / document processing / error. */
    CLASSIFIED,

    /** Running document FSM for each document attachment. */
    DOCUMENTS_PROCESSING,

    /** FSM results aggregated from all document contexts. */
    RESULTS_COLLECTED,

    /** RAG chunks applied to user query. */
    QUERY_AUGMENTED,

    /** Follow-up RAG: augmenting from stored document IDs (no new documents). */
    FOLLOW_UP_RAG,

    // --- Terminal states ---

    /** No processing needed — command passed directly to factory. */
    PASSTHROUGH,

    /** Orchestrated command built with augmented query and modified attachments. */
    COMMAND_BUILT,

    /** Unsupported attachment type detected. */
    ERROR
}
