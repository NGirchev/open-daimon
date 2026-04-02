package io.github.ngirchev.opendaimon.telegram.service.fsm;

/**
 * Actions invoked by the coalescing FSM during state transitions.
 *
 * <p>Implementations check conditions and set decision flags on the
 * {@link CoalescingContext}, as well as the final result action.
 */
public interface CoalescingActions {

    /**
     * Check if coalescing is enabled and extract user-chat key.
     * Called during RECEIVED → ENABLED_CHECKED transition.
     *
     * <p>Sets {@link CoalescingContext#isEnabled()}, {@link CoalescingContext#isHasKey()}.
     * If disabled or no key, sets result to ProcessSingle.
     */
    void checkEnabled(CoalescingContext ctx);

    /**
     * Check pending message and merge eligibility.
     * Called during ENABLED_CHECKED → PENDING_CHECKED transition.
     *
     * <p>Sets {@link CoalescingContext#isHasPending()},
     * {@link CoalescingContext#isCanMerge()},
     * {@link CoalescingContext#isFirstCandidate()}.
     */
    void checkPending(CoalescingContext ctx);

    /**
     * Merge pending with current update.
     * Called during PENDING_CHECKED → PROCESS_MERGED transition.
     *
     * <p>Removes pending, sets result to ProcessMerged.
     */
    void merge(CoalescingContext ctx);

    /**
     * Flush pending and process current separately.
     * Called during PENDING_CHECKED → PROCESS_BOTH transition.
     *
     * <p>Removes pending, sets result to ProcessPendingAndCurrent.
     */
    void flushBoth(CoalescingContext ctx);

    /**
     * Hold current update as first candidate, schedule timeout.
     * Called during PENDING_CHECKED → WAIT_FOR_PAIR transition.
     *
     * <p>Sets result to WaitForPossiblePair.
     */
    void holdCandidate(CoalescingContext ctx);

    /**
     * Process current update as-is (not a first candidate, no pending).
     * Called during PENDING_CHECKED → PROCESS_SINGLE transition.
     *
     * <p>Sets result to ProcessSingle.
     */
    void processSingle(CoalescingContext ctx);
}
