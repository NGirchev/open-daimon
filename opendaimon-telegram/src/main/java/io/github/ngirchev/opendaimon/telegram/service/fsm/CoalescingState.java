package io.github.ngirchev.opendaimon.telegram.service.fsm;

/**
 * States for the message coalescing decision FSM.
 *
 * <p>Models the decision tree for incoming Telegram updates:
 * should we wait for a pair, merge, flush, or process immediately?
 *
 * <p>Terminal states: {@link #PROCESS_SINGLE}, {@link #PROCESS_MERGED},
 * {@link #PROCESS_BOTH}, {@link #WAIT_FOR_PAIR}.
 */
public enum CoalescingState {

    /** Initial state — update received. */
    RECEIVED,

    /** Coalescing enabled/disabled checked, user-chat key extracted. */
    ENABLED_CHECKED,

    /** Pending message presence and merge eligibility checked. */
    PENDING_CHECKED,

    // --- Terminal states ---

    /** Process the update as-is (no coalescing). */
    PROCESS_SINGLE,

    /** Two updates merged into one. */
    PROCESS_MERGED,

    /** Pending and current processed separately (can't merge). */
    PROCESS_BOTH,

    /** Current update held as first candidate, waiting for possible pair. */
    WAIT_FOR_PAIR
}
