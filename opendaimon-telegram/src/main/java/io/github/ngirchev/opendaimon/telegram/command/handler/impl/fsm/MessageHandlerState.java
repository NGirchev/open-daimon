package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

/**
 * States for the Telegram message handler FSM.
 *
 * <p>Models the full message processing lifecycle: from receiving the message
 * through AI response generation to sending the result back to the user.
 *
 * <p>Terminal states: {@link #COMPLETED}, {@link #ERROR}.
 *
 * <p>Transition graph:
 * <pre>
 * RECEIVED ──[HANDLE]──▶ USER_RESOLVED
 *
 * USER_RESOLVED ──[auto]──▶ INPUT_VALIDATED
 *
 * INPUT_VALIDATED ──[auto]──┬─[isEmpty]──▶ ERROR (terminal)
 *                           └─[hasInput]─▶ MESSAGE_SAVED
 *
 * MESSAGE_SAVED ──[auto]──▶ METADATA_PREPARED
 *
 * METADATA_PREPARED ──[auto]──▶ COMMAND_CREATED
 *
 * COMMAND_CREATED ──[auto]──┬─[hasError]──▶ ERROR (terminal)
 *                           └─[success]───▶ RESPONSE_GENERATED
 *
 * RESPONSE_GENERATED ──[auto]──┬─[hasResponse]──▶ RESPONSE_SAVED
 *                              └─[noResponse]───▶ ERROR (terminal)
 *
 * RESPONSE_SAVED ──[auto]──▶ COMPLETED (terminal)
 * </pre>
 */
public enum MessageHandlerState {

    /** Initial state — message received. */
    RECEIVED,

    /** User and session resolved/created. */
    USER_RESOLVED,

    /** Input validated (non-empty text or attachments). */
    INPUT_VALIDATED,

    /** User message saved to database. */
    MESSAGE_SAVED,

    /** Metadata prepared: thread, role, RAG doc IDs, reply attachments. */
    METADATA_PREPARED,

    /** AI command created via pipeline. */
    COMMAND_CREATED,

    /** AI response generated (with guardrail retry and empty retry if needed). */
    RESPONSE_GENERATED,

    /** Response saved to database. */
    RESPONSE_SAVED,

    // --- Terminal states ---

    /** Processing completed successfully — response sent to user. */
    COMPLETED,

    /** Error occurred — error info stored in context for handler to dispatch. */
    ERROR
}
