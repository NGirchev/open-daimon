package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

/**
 * Actions invoked by the message handler FSM during state transitions.
 *
 * <p>Each method corresponds to a processing step. Implementations populate
 * the {@link MessageHandlerContext} with intermediate and final results.
 *
 * <p>Actions must not throw exceptions for expected failures. Instead, they set
 * {@link MessageHandlerContext#setErrorType(MessageHandlerErrorType)} and
 * {@link MessageHandlerContext#setException(Exception)} so that the FSM routes
 * to the ERROR terminal state. The handler dispatches to the appropriate error
 * handling method after the FSM completes.
 */
public interface MessageHandlerActions {

    /**
     * Resolve Telegram user and session.
     * Called during RECEIVED → USER_RESOLVED transition.
     *
     * <p>Sets {@link MessageHandlerContext#getTelegramUser()},
     * {@link MessageHandlerContext#getSession()}.
     */
    void resolveUser(MessageHandlerContext ctx);

    /**
     * Validate that input is not empty (text or attachments present).
     * Called during USER_RESOLVED → INPUT_VALIDATED transition.
     *
     * <p>Sets {@link MessageHandlerContext#hasInput()}.
     * If empty, sets error type to {@link MessageHandlerErrorType#INPUT_EMPTY}.
     */
    void validateInput(MessageHandlerContext ctx);

    /**
     * Save the user message to database.
     * Called during INPUT_VALIDATED → MESSAGE_SAVED transition.
     *
     * <p>Sets {@link MessageHandlerContext#getUserMessage()},
     * {@link MessageHandlerContext#getThread()},
     * {@link MessageHandlerContext#getAssistantRole()}.
     */
    void saveMessage(MessageHandlerContext ctx);

    /**
     * Prepare metadata: thread key, role, RAG doc IDs, reply image attachments.
     * Called during MESSAGE_SAVED → METADATA_PREPARED transition.
     *
     * <p>Sets {@link MessageHandlerContext#getMetadata()}.
     */
    void prepareMetadata(MessageHandlerContext ctx);

    /**
     * Create AI command via pipeline and resolve gateway.
     * Called during METADATA_PREPARED → COMMAND_CREATED transition.
     *
     * <p>Catches {@code UserMessageTooLongException},
     * {@code DocumentContentNotExtractableException},
     * {@code UnsupportedModelCapabilityException} and sets error info on context.
     *
     * <p>Sets {@link MessageHandlerContext#getAiCommand()},
     * {@link MessageHandlerContext#getAiGateway()}.
     */
    void createCommand(MessageHandlerContext ctx);

    /**
     * Generate AI response with guardrail retry and empty content retry.
     * Called during COMMAND_CREATED → RESPONSE_GENERATED transition.
     *
     * <p>For streaming responses, sends text paragraphs via
     * {@link MessageHandlerContext#getStreamingParagraphSender()}.
     *
     * <p>Sets response data: text, error, useful data, streaming flag.
     * On failure, sets error info on context.
     */
    void generateResponse(MessageHandlerContext ctx);

    /**
     * Save assistant response to database and update RAG metadata.
     * Called during RESPONSE_GENERATED → RESPONSE_SAVED transition.
     *
     * <p>Saves the assistant message and updates thread counters.
     */
    void saveResponse(MessageHandlerContext ctx);

}
