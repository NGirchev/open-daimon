package io.github.ngirchev.opendaimon.telegram.service.fsm;

/**
 * Events that drive the coalescing FSM.
 *
 * <p>Only {@link #EVALUATE} is fired externally.
 */
public enum CoalescingEvent {

    /** Evaluate an incoming update for coalescing. */
    EVALUATE
}
