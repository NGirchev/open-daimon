package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

/**
 * Error types for the message handler FSM.
 *
 * <p>Stored in {@link MessageHandlerContext} when an error occurs during processing.
 * The handler dispatches to the appropriate error handling method based on this type
 * after the FSM completes.
 */
public enum MessageHandlerErrorType {

    /** Empty input — no text and no attachments. */
    INPUT_EMPTY,

    /** User message exceeds token limit. */
    MESSAGE_TOO_LONG,

    /** Document text extraction failed. */
    DOCUMENT_NOT_EXTRACTABLE,

    /** Model does not support required capabilities. */
    UNSUPPORTED_CAPABILITY,

    /** Context summarization failed — thread too long. */
    SUMMARIZATION_FAILED,

    /** AI response has empty content after retry. */
    EMPTY_RESPONSE,

    /** Telegram refused all retries to deliver the final answer (429 flood, network, etc). */
    TELEGRAM_DELIVERY_FAILED,

    /** General/unexpected error during processing. */
    GENERAL
}
