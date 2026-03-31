package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

import io.github.ngirchev.fsm.Action;
import io.github.ngirchev.fsm.FsmFactory;
import io.github.ngirchev.fsm.Guard;
import io.github.ngirchev.fsm.StateContext;
import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;

import java.util.function.Consumer;
import java.util.function.Predicate;

import static io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerEvent.HANDLE;
import static io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerState.*;

/**
 * Creates the message handler FSM with all transitions defined declaratively.
 *
 * <p>The FSM uses auto-transitions: a single {@link MessageHandlerEvent#HANDLE} event
 * triggers the initial transition, then the FSM automatically chains through states
 * based on conditions (guards) until reaching a terminal state.
 *
 * <p>Error handling strategy: actions catch exceptions and set error info on context
 * (errorType + exception). Guards detect errors and route to the ERROR terminal state.
 * The handler dispatches to specific error handling methods after FSM completes.
 *
 * <p>Streaming: the generateResponse action sends text paragraphs in real-time
 * via the context's streaming callback. The sendResponse action sends only
 * the keyboard (streaming) or the full text + keyboard (non-streaming).
 */
public final class MessageHandlerFsmFactory {

    private MessageHandlerFsmFactory() {
    }

    /**
     * Creates a stateless domain FSM that processes {@link MessageHandlerContext} objects.
     *
     * @param actions implementation of handler actions (injected by Spring)
     * @return domain FSM ready to process message handler contexts
     */
    public static ExDomainFsm<MessageHandlerContext, MessageHandlerState, MessageHandlerEvent> create(
            MessageHandlerActions actions) {

        var table = FsmFactory.INSTANCE.<MessageHandlerState, MessageHandlerEvent>statesWithEvents()
                .autoTransitionEnabled(true)

                // === RECEIVED → USER_RESOLVED (event-driven: HANDLE) ===
                .from(RECEIVED).onEvent(HANDLE).to(USER_RESOLVED)
                    .action(action(actions::resolveUser))
                    .end()

                // === USER_RESOLVED → INPUT_VALIDATED (auto) ===
                .from(USER_RESOLVED).toMultiple()
                    .to(INPUT_VALIDATED)
                        .action(action(actions::validateInput))
                        .end()
                    .endMultiple()

                // === INPUT_VALIDATED → branch (auto) ===
                .from(INPUT_VALIDATED).toMultiple()
                    .to(ERROR)
                        .condition(guard(MessageHandlerContext::hasError))
                        .end()
                    .to(MESSAGE_SAVED)
                        .action(action(actions::saveMessage))
                        .end()
                    .endMultiple()

                // === MESSAGE_SAVED → METADATA_PREPARED (auto) ===
                .from(MESSAGE_SAVED).toMultiple()
                    .to(METADATA_PREPARED)
                        .action(action(actions::prepareMetadata))
                        .end()
                    .endMultiple()

                // === METADATA_PREPARED → COMMAND_CREATED (auto) ===
                .from(METADATA_PREPARED).toMultiple()
                    .to(COMMAND_CREATED)
                        .action(action(actions::createCommand))
                        .end()
                    .endMultiple()

                // === COMMAND_CREATED → branch: success or error (auto) ===
                .from(COMMAND_CREATED).toMultiple()
                    .to(ERROR)
                        .condition(guard(MessageHandlerContext::hasError))
                        .end()
                    .to(RESPONSE_GENERATED)
                        .action(action(actions::generateResponse))
                        .end()
                    .endMultiple()

                // === RESPONSE_GENERATED → branch: has response or error (auto) ===
                .from(RESPONSE_GENERATED).toMultiple()
                    .to(ERROR)
                        .condition(guard(MessageHandlerContext::hasError))
                        .end()
                    .to(RESPONSE_SAVED)
                        .condition(guard(MessageHandlerContext::hasResponse))
                        .action(action(actions::saveResponse))
                        .end()
                    .to(ERROR)
                        .end()
                    .endMultiple()

                // === RESPONSE_SAVED → COMPLETED (auto, no action — handler sends response) ===
                .from(RESPONSE_SAVED).toMultiple()
                    .to(COMPLETED)
                        .end()
                    .endMultiple()

                .build();

        return table.createDomainFsm();
    }

    private static Guard<StateContext<MessageHandlerState>> guard(
            Predicate<MessageHandlerContext> predicate) {
        return ctx -> predicate.test((MessageHandlerContext) ctx);
    }

    private static Action<StateContext<MessageHandlerState>> action(
            Consumer<MessageHandlerContext> consumer) {
        return ctx -> consumer.accept((MessageHandlerContext) ctx);
    }
}
