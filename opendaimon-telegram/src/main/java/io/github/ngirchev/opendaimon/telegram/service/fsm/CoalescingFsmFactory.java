package io.github.ngirchev.opendaimon.telegram.service.fsm;

import io.github.ngirchev.fsm.Action;
import io.github.ngirchev.fsm.FsmFactory;
import io.github.ngirchev.fsm.Guard;
import io.github.ngirchev.fsm.StateContext;
import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;

import java.util.function.Consumer;
import java.util.function.Predicate;

import static io.github.ngirchev.opendaimon.telegram.service.fsm.CoalescingEvent.EVALUATE;
import static io.github.ngirchev.opendaimon.telegram.service.fsm.CoalescingState.*;

/**
 * Creates the coalescing FSM with all transitions defined declaratively.
 *
 * <p>Transition graph:
 * <pre>
 * RECEIVED ──[EVALUATE]──▶ ENABLED_CHECKED
 *     action: checkEnabled()
 *
 * ENABLED_CHECKED ──[auto]──┬─[disabled]──▶ PROCESS_SINGLE (terminal)
 *                           └─[enabled]───▶ PENDING_CHECKED
 *                               action: checkPending()
 *
 * PENDING_CHECKED ──[auto]──┬─[canMerge]────────▶ PROCESS_MERGED (terminal)
 *                           │   action: merge()
 *                           ├─[pendingNoMerge]──▶ PROCESS_BOTH (terminal)
 *                           │   action: flushBoth()
 *                           ├─[firstCandidate]──▶ WAIT_FOR_PAIR (terminal)
 *                           │   action: holdCandidate()
 *                           └─[else]────────────▶ PROCESS_SINGLE (terminal)
 *                               action: processSingle()
 * </pre>
 */
public final class CoalescingFsmFactory {

    private CoalescingFsmFactory() {
    }

    public static ExDomainFsm<CoalescingContext, CoalescingState, CoalescingEvent> create(
            CoalescingActions actions) {

        var table = FsmFactory.INSTANCE.<CoalescingState, CoalescingEvent>statesWithEvents()
                .autoTransitionEnabled(true)

                // === RECEIVED → ENABLED_CHECKED (event-driven: EVALUATE) ===
                .from(RECEIVED).onEvent(EVALUATE).to(ENABLED_CHECKED)
                    .action(action(actions::checkEnabled))
                    .end()

                // === ENABLED_CHECKED → branch (auto) ===
                .from(ENABLED_CHECKED).toMultiple()
                    .to(PROCESS_SINGLE)
                        .condition(guard(CoalescingContext::isDisabled))
                        .end()
                    .to(PENDING_CHECKED)
                        .action(action(actions::checkPending))
                        .end()
                    .endMultiple()

                // === PENDING_CHECKED → branch (auto) ===
                .from(PENDING_CHECKED).toMultiple()
                    .to(PROCESS_MERGED)
                        .condition(guard(CoalescingContext::isCanMerge))
                        .action(action(actions::merge))
                        .end()
                    .to(PROCESS_BOTH)
                        .condition(guard(CoalescingContext::isPendingNoMerge))
                        .action(action(actions::flushBoth))
                        .end()
                    .to(WAIT_FOR_PAIR)
                        .condition(guard(CoalescingContext::isIsFirstCandidate))
                        .action(action(actions::holdCandidate))
                        .end()
                    .to(PROCESS_SINGLE)
                        .action(action(actions::processSingle))
                        .end()
                    .endMultiple()

                .build();

        return table.createDomainFsm();
    }

    private static Guard<StateContext<CoalescingState>> guard(
            Predicate<CoalescingContext> predicate) {
        return ctx -> predicate.test((CoalescingContext) ctx);
    }

    private static Action<StateContext<CoalescingState>> action(
            Consumer<CoalescingContext> consumer) {
        return ctx -> consumer.accept((CoalescingContext) ctx);
    }
}
