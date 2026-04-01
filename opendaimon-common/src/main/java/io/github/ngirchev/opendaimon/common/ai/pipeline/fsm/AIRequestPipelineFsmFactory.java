package io.github.ngirchev.opendaimon.common.ai.pipeline.fsm;

import io.github.ngirchev.fsm.Action;
import io.github.ngirchev.fsm.FsmFactory;
import io.github.ngirchev.fsm.Guard;
import io.github.ngirchev.fsm.StateContext;
import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;

import java.util.function.Consumer;
import java.util.function.Predicate;

import static io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AIRequestEvent.PREPARE;
import static io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AIRequestState.*;

/**
 * Creates the AI request pipeline FSM with all transitions defined declaratively.
 *
 * <p>The FSM uses auto-transitions: a single {@link AIRequestEvent#PREPARE} event
 * triggers the initial transition, then the FSM automatically chains through states
 * based on conditions (guards) until reaching a terminal state.
 *
 * <p>Transition graph:
 * <pre>
 * RECEIVED ──[PREPARE]──▶ VALIDATED
 *     action: validate()
 *
 * VALIDATED ──[auto]──┬─[notChatCommand]──▶ PASSTHROUGH (terminal)
 *                     │   action: buildPassthrough()
 *                     └─[isChatCommand]───▶ CLASSIFIED
 *                         action: classify()
 *
 * CLASSIFIED ──[auto]──┬─[hasUnrecognized]──▶ ERROR (terminal)
 *                      │   action: handleError()
 *                      ├─[isPassthrough]────▶ PASSTHROUGH (terminal)
 *                      │   action: buildPassthrough()
 *                      ├─[isFollowUpRag]────▶ FOLLOW_UP_RAG
 *                      │   action: processFollowUpRag()
 *                      └─[hasDocuments]─────▶ DOCUMENTS_PROCESSING
 *                          action: processDocuments()
 *
 * FOLLOW_UP_RAG ──[auto]──▶ COMMAND_BUILT (terminal)
 *     action: buildCommand()
 *
 * DOCUMENTS_PROCESSING ──[auto]──▶ RESULTS_COLLECTED
 *     action: collectResults()
 *
 * RESULTS_COLLECTED ──[auto]──▶ QUERY_AUGMENTED
 *     action: augmentQuery()
 *
 * QUERY_AUGMENTED ──[auto]──▶ COMMAND_BUILT (terminal)
 *     action: buildCommand()
 * </pre>
 */
public final class AIRequestPipelineFsmFactory {

    private AIRequestPipelineFsmFactory() {
    }

    /**
     * Creates a stateless domain FSM that processes {@link AIRequestContext} objects.
     *
     * <p>The returned FSM is thread-safe and can be shared as a singleton Spring bean.
     * Each {@code handle(context, PREPARE)} call creates an internal FSM instance
     * scoped to that context.
     *
     * @param actions implementation of pipeline actions (injected by Spring)
     * @return domain FSM ready to process request contexts
     */
    public static ExDomainFsm<AIRequestContext, AIRequestState, AIRequestEvent> create(
            AIRequestPipelineActions actions) {

        var table = FsmFactory.INSTANCE.<AIRequestState, AIRequestEvent>statesWithEvents()
                .autoTransitionEnabled(true)

                // === RECEIVED → VALIDATED (event-driven: PREPARE) ===
                .from(RECEIVED).onEvent(PREPARE).to(VALIDATED)
                    .action(action(actions::validate))
                    .end()

                // === VALIDATED → branch (auto-transition) ===
                .from(VALIDATED).toMultiple()
                    .to(PASSTHROUGH)
                        .onCondition(guard(AIRequestContext::isNotChatCommand))
                        .action(action(actions::buildPassthrough))
                        .end()
                    .to(CLASSIFIED)
                        .action(action(actions::classify))
                        .end()
                    .endMultiple()

                // === CLASSIFIED → branch by routing decision (auto-transition) ===
                .from(CLASSIFIED).toMultiple()
                    .to(ERROR)
                        .onCondition(guard(AIRequestContext::hasUnrecognized))
                        .action(action(actions::handleError))
                        .end()
                    .to(PASSTHROUGH)
                        .onCondition(guard(AIRequestContext::isPassthrough))
                        .action(action(actions::buildPassthrough))
                        .end()
                    .to(FOLLOW_UP_RAG)
                        .onCondition(guard(AIRequestContext::isFollowUpRag))
                        .action(action(actions::processFollowUpRag))
                        .end()
                    .to(DOCUMENTS_PROCESSING)
                        .action(action(actions::processDocuments))
                        .end()
                    .endMultiple()

                // === FOLLOW_UP_RAG → COMMAND_BUILT (auto-transition) ===
                .from(FOLLOW_UP_RAG).toMultiple()
                    .to(COMMAND_BUILT)
                        .action(action(actions::buildCommand))
                        .end()
                    .endMultiple()

                // === DOCUMENTS_PROCESSING → RESULTS_COLLECTED (auto-transition) ===
                .from(DOCUMENTS_PROCESSING).toMultiple()
                    .to(RESULTS_COLLECTED)
                        .action(action(actions::collectResults))
                        .end()
                    .endMultiple()

                // === RESULTS_COLLECTED → QUERY_AUGMENTED (auto-transition) ===
                .from(RESULTS_COLLECTED).toMultiple()
                    .to(QUERY_AUGMENTED)
                        .action(action(actions::augmentQuery))
                        .end()
                    .endMultiple()

                // === QUERY_AUGMENTED → COMMAND_BUILT (auto-transition) ===
                .from(QUERY_AUGMENTED).toMultiple()
                    .to(COMMAND_BUILT)
                        .action(action(actions::buildCommand))
                        .end()
                    .endMultiple()

                .build();

        return table.createDomainFsm();
    }

    /**
     * Adapts a typed predicate on {@link AIRequestContext} to a
     * {@link Guard} on {@code StateContext<AIRequestState>} required by the FSM library.
     */
    private static Guard<StateContext<AIRequestState>> guard(
            Predicate<AIRequestContext> predicate) {
        return ctx -> predicate.test((AIRequestContext) ctx);
    }

    /**
     * Adapts a typed consumer on {@link AIRequestContext} to an
     * {@link Action} on {@code StateContext<AIRequestState>} required by the FSM library.
     */
    private static Action<StateContext<AIRequestState>> action(
            Consumer<AIRequestContext> consumer) {
        return ctx -> consumer.accept((AIRequestContext) ctx);
    }
}
